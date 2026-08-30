package com.allfolio.domain.service;

import com.allfolio.domain.RefreshToken;
import com.allfolio.domain.User;
import com.allfolio.domain.exception.EmailAlreadyExistsException;
import com.allfolio.domain.exception.InvalidCredentialsException;
import com.allfolio.domain.exception.RefreshTokenInvalidException;
import com.allfolio.domain.repository.RefreshTokenRepository;
import com.allfolio.domain.repository.UserRepository;
import com.allfolio.infra.logging.LogMarkers;
import com.allfolio.infra.security.JwtIssuer;
import com.allfolio.infra.security.RefreshTokenIssuer;
import com.allfolio.web.dto.LoginRequest;
import com.allfolio.web.dto.LogoutRequest;
import com.allfolio.web.dto.RefreshRequest;
import com.allfolio.web.dto.SignupRequest;
import com.allfolio.web.dto.TokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * 회원가입·로그인·Refresh Token 발급/회전/폐기 (docs/ROADMAP.md Task 003·019).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** user enumeration 방지 — 이메일 없음/비밀번호 불일치에 동일 메시지를 쓴다. */
    private static final String INVALID_CREDENTIALS_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";

    private static final String INVALID_REFRESH_TOKEN_MESSAGE = "유효하지 않거나 만료된 토큰입니다.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final RefreshTokenRepository refreshTokenRepository;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtIssuer jwtIssuer,
            RefreshTokenIssuer refreshTokenIssuer, RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtIssuer = jwtIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public TokenResponse signup(SignupRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("이미 가입된 이메일입니다.");
        }

        User user = userRepository.save(User.of(email, passwordEncoder.encode(request.password())));
        // INSERT는 uk_users_email 경합 시 커밋 시점에야 실패할 수 있다(동시 가입 레이스) —
        // 커밋 전에 로그를 남기면 실제로는 실패한 회원가입이 감사 로그엔 "성공"으로 남는다.
        UUID userId = user.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info(LogMarkers.AUDIT, "회원가입 성공 userId={}", userId);
            }
        });
        return issueToken(user);
    }

    /**
     * 존재하지 않는 이메일이면 BCrypt 검증 없이 즉시 실패하므로 응답 시간에 차이가 생긴다.
     * 타이밍 공격 대응(더미 해시 비교)은 향후 과제로 유예한다(ROADMAP 미배정).
     *
     * <p>readOnly가 아니다 — issueToken()이 Refresh Token을 신규 저장(Task 019)하므로,
     * readOnly=true로 두면 Hibernate가 FlushMode.MANUAL로 전환해 INSERT가 조용히 버려진다
     * (예외 없이 응답에는 토큰이 담기지만 DB에는 저장되지 않아 이후 refresh 호출이 401로 실패하는
     * 형태로 실측됨).
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalize(request.email()))
                .orElseThrow(() -> {
                    log.warn(LogMarkers.AUDIT, "로그인 실패");
                    return new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn(LogMarkers.AUDIT, "로그인 실패");
            throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        // issueToken()이 Refresh Token을 신규 INSERT하므로, signup()과 동일한 이유로
        // 커밋 전 로깅은 "실제로는 실패한 로그인"을 감사 로그엔 "성공"으로 남길 수 있다.
        UUID userId = user.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info(LogMarkers.AUDIT, "로그인 성공 userId={}", userId);
            }
        });
        return issueToken(user);
    }

    /**
     * 제시된 Refresh Token을 검증하고, 유효하면 그 토큰을 폐기(rotation)한 뒤 새 Access+Refresh 토큰
     * 쌍을 발급한다. rotation은 탈취된 토큰의 재사용을 막기 위함이다.
     */
    @Transactional
    public TokenResponse refresh(RefreshRequest request) {
        RefreshToken refreshToken = findValidRefreshToken(request.refreshToken());
        refreshToken.revoke();
        refreshTokenRepository.save(refreshToken);

        // 기존 토큰 revoke + 신규 토큰 저장(issueToken) 모두 커밋 전에는 실패 가능성이 남아있다.
        UUID userId = refreshToken.getUser().getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info(LogMarkers.AUDIT, "토큰 갱신 성공 userId={}", userId);
            }
        });
        return issueToken(refreshToken.getUser());
    }

    /** 이미 로그아웃된 토큰으로 다시 호출해도 예외 없이 조용히 끝난다(idempotent). */
    @Transactional
    public void logout(LogoutRequest request) {
        String hash = refreshTokenIssuer.hash(request.refreshToken());
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            token.revoke();
            refreshTokenRepository.save(token);

            UUID userId = token.getUser().getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info(LogMarkers.AUDIT, "로그아웃 성공 userId={}", userId);
                }
            });
        });
    }

    private RefreshToken findValidRefreshToken(String rawToken) {
        String hash = refreshTokenIssuer.hash(rawToken);
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> {
                    log.warn(LogMarkers.AUDIT, "Refresh Token 갱신 실패 — 존재하지 않는 토큰");
                    return new RefreshTokenInvalidException(INVALID_REFRESH_TOKEN_MESSAGE);
                });

        if (refreshToken.getRevokedAt() != null || refreshToken.getExpiresAt().isBefore(Instant.now())) {
            log.warn(LogMarkers.AUDIT, "Refresh Token 갱신 실패 — 폐기되었거나 만료된 토큰 userId={}",
                    refreshToken.getUser().getId());
            throw new RefreshTokenInvalidException(INVALID_REFRESH_TOKEN_MESSAGE);
        }
        return refreshToken;
    }

    private TokenResponse issueToken(User user) {
        String rawRefreshToken = refreshTokenIssuer.issue();
        RefreshToken refreshToken = RefreshToken.of(user, refreshTokenIssuer.hash(rawRefreshToken),
                refreshTokenIssuer.expiresAt());
        refreshTokenRepository.save(refreshToken);
        return TokenResponse.bearer(jwtIssuer.issue(user.getId()), rawRefreshToken,
                jwtIssuer.accessTokenExpiresInSeconds());
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
