package com.allfolio.infra.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Refresh Token 발급·해시 (docs/ROADMAP.md Task 019).
 *
 * <p>Refresh Token은 JWT가 아니다 — 서버가 개별 토큰을 무효화(로그아웃)할 수 있어야 하므로,
 * SecureRandom으로 생성한 무작위 문자열을 원문으로 클라이언트에 발급하고, DB에는 SHA-256 해시만 저장한다.
 * 서명 검증 책임({@link JwtIssuer})과 "무작위 생성 + 해시" 책임을 의도적으로 분리해 둔다.
 *
 * <p>{@code User.passwordHash}는 무차별 대입 공격을 늦추기 위해 BCrypt(느린 해시)를 쓰지만,
 * 이 클래스가 만드는 해시는 비밀번호가 아니라 이미 SecureRandom으로 생성된 고엔트로피 무작위 토큰의
 * 저장·조회용 단방향 다이제스트일 뿐이다. 오프라인 사전 대입 공격의 대상이 되지 않으므로
 * BCrypt처럼 느릴 필요가 없고, SHA-256으로 충분하다.
 */
@Component
public class RefreshTokenIssuer {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Duration refreshTokenTtl;

    public RefreshTokenIssuer(JwtProperties properties) {
        this.refreshTokenTtl = properties.refreshTokenTtl();
    }

    /** SecureRandom 32바이트를 Base64 URL-safe(패딩 없음)로 인코딩한 원문 토큰을 발급한다. */
    public String issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 원문 토큰의 SHA-256 hex 다이제스트(64자)를 계산한다. DB 저장·조회는 이 값으로 한다. */
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 다이제스트를 생성하지 못했습니다.", e);
        }
    }

    /** 설정된 refresh-token-ttl(기본 14일)만큼 지금으로부터 더한 만료 시각을 계산한다. */
    public Instant expiresAt() {
        return Instant.now().plus(refreshTokenTtl);
    }
}
