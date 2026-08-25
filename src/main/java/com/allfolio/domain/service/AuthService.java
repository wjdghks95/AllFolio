package com.allfolio.domain.service;

import com.allfolio.domain.User;
import com.allfolio.domain.exception.EmailAlreadyExistsException;
import com.allfolio.domain.exception.InvalidCredentialsException;
import com.allfolio.domain.repository.UserRepository;
import com.allfolio.infra.logging.LogMarkers;
import com.allfolio.infra.security.JwtIssuer;
import com.allfolio.web.dto.LoginRequest;
import com.allfolio.web.dto.SignupRequest;
import com.allfolio.web.dto.TokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.UUID;

/**
 * 회원가입·로그인 (docs/ROADMAP.md Task 003). Refresh Token은 Task 019로 유예한다.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** user enumeration 방지 — 이메일 없음/비밀번호 불일치에 동일 메시지를 쓴다. */
    private static final String INVALID_CREDENTIALS_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtIssuer jwtIssuer) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtIssuer = jwtIssuer;
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
     */
    @Transactional(readOnly = true)
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

        log.info(LogMarkers.AUDIT, "로그인 성공 userId={}", user.getId());
        return issueToken(user);
    }

    private TokenResponse issueToken(User user) {
        return TokenResponse.bearer(jwtIssuer.issue(user.getId()), jwtIssuer.accessTokenExpiresInSeconds());
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
