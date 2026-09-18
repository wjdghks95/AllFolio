package com.allfolio.domain.repository;

import com.allfolio.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** POST /v1/auth/refresh, POST /v1/auth/logout에서 제시된 토큰의 SHA-256 해시로 단건 조회. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 만료됐거나(rotation·자연 만료) 폐기된(로그아웃) 지 {@code cutoff} 이전인 행을 정리한다
     * (RefreshTokenCleanupScheduler, ROADMAP Task 019 남은 갭 해소).
     *
     * <p>{@code revokedAt}이 null인 아직 유효한 토큰은 {@code r.revokedAt < :cutoff}가 언노운으로
     * 평가돼 자동으로 제외된다.
     *
     * <p>Spring Data JPA는 base 리포지토리(SimpleJpaRepository)가 구현하지 않는 커스텀 메서드에는
     * 클래스 레벨 {@code @Transactional}을 상속시키지 않는다({@code TransactionalRepositoryProxyPostProcessor}
     * 바이트코드로 확인, 인터페이스 메서드 자체에 애노테이션이 있어야 인식) — 트랜잭션 없이
     * {@code em.remove()}를 호출하면 {@code TransactionRequiredException}이 나므로 이 메서드에
     * 직접 붙여야 한다.
     */
    @Modifying
    @Transactional
    @Query("delete from RefreshToken r where r.expiresAt < :cutoff or r.revokedAt < :cutoff")
    int deleteExpiredOrRevokedBefore(@Param("cutoff") Instant cutoff);
}
