package com.allfolio.infra.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * HS256 Access Token 발급·검증 (docs/ROADMAP.md Task 003). 현재는 대칭키이며, 향후 RS256/JWKS 전환을 검토할 수 있다(ROADMAP 미배정).
 */
@Component
public class JwtIssuer {

    private static final Logger log = LoggerFactory.getLogger(JwtIssuer.class);

    private final MACSigner signer;
    private final MACVerifier verifier;
    private final long ttlSeconds;

    public JwtIssuer(JwtProperties properties) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        try {
            this.signer = new MACSigner(secret);
            this.verifier = new MACVerifier(secret);
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT 시크릿으로 HS256 서명기를 생성하지 못했습니다.", e);
        }
        this.ttlSeconds = properties.accessTokenTtl().toSeconds();
    }

    public String issue(UUID userId) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Access Token 서명에 실패했습니다.", e);
        }
        return jwt.serialize();
    }

    /**
     * 토큰을 검증하고 사용자 ID를 돌려준다. 실패 사유는 노출하지 않고 empty로 통일한다.
     * 로그에도 토큰 원문은 남기지 않는다.
     */
    public Optional<UUID> resolveUserId(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            log.debug("JWT 파싱 실패");
            return Optional.empty();
        }

        // alg 혼동 공격(alg:none 등) 방어 — 헤더 알고리즘을 명시적으로 고정한다.
        if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
            log.debug("JWT 알고리즘 불일치: {}", jwt.getHeader().getAlgorithm());
            return Optional.empty();
        }

        try {
            if (!jwt.verify(verifier)) {
                log.debug("JWT 서명 검증 실패");
                return Optional.empty();
            }
        } catch (JOSEException e) {
            log.debug("JWT 서명 검증 중 오류");
            return Optional.empty();
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            log.debug("JWT 클레임 파싱 실패");
            return Optional.empty();
        }

        // nimbus는 exp를 자동 검사하지 않는다 — 직접 확인해야 한다.
        Date expiration = claims.getExpirationTime();
        if (expiration == null || !expiration.toInstant().isAfter(Instant.now())) {
            log.debug("JWT 만료 또는 exp 누락");
            return Optional.empty();
        }

        try {
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (IllegalArgumentException | NullPointerException e) {
            log.debug("JWT sub가 유효한 UUID가 아님");
            return Optional.empty();
        }
    }

    public long accessTokenExpiresInSeconds() {
        return ttlSeconds;
    }
}
