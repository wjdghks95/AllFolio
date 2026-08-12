package com.allfolio.domain.service;

import com.allfolio.domain.User;
import com.allfolio.domain.exception.EmailAlreadyExistsException;
import com.allfolio.domain.exception.InvalidCredentialsException;
import com.allfolio.domain.repository.UserRepository;
import com.allfolio.infra.security.JwtIssuer;
import com.allfolio.web.dto.LoginRequest;
import com.allfolio.web.dto.SignupRequest;
import com.allfolio.web.dto.TokenResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 회원가입·로그인. Phase 1 범위이며 Refresh Token은 Phase 2로 유예한다 (PHASE1_PLAN.md Step 3).
 */
@Service
public class AuthService {

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
        return issueToken(user);
    }

    /**
     * 존재하지 않는 이메일이면 BCrypt 검증 없이 즉시 실패하므로 응답 시간에 차이가 생긴다.
     * 타이밍 공격 대응(더미 해시 비교)은 Phase 1에서 유예한다.
     */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalize(request.email()))
                .orElseThrow(() -> new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        return issueToken(user);
    }

    private TokenResponse issueToken(User user) {
        return TokenResponse.bearer(jwtIssuer.issue(user.getId()), jwtIssuer.accessTokenExpiresInSeconds());
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
