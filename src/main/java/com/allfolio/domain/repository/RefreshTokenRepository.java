package com.allfolio.domain.repository;

import com.allfolio.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** POST /v1/auth/refresh, POST /v1/auth/logout에서 제시된 토큰의 SHA-256 해시로 단건 조회. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
