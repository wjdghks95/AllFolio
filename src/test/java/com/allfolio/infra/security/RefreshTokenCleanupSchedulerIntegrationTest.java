package com.allfolio.infra.security;

import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.RefreshToken;
import com.allfolio.domain.User;
import com.allfolio.domain.repository.RefreshTokenRepository;
import com.allfolio.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * shrimp-task-manager "만료된 refresh_tokens 정리 배치 신설" — ROADMAP Task 019 남은 갭 해소.
 * 실제 {@code @Scheduled} cron 타이머를 기다리지 않고 {@link RefreshTokenCleanupScheduler#cleanup()}을
 * 직접 호출해, 만료·폐기된 지 유예기간(7일)이 지난 행만 삭제되고 활성 토큰은 남는지 확인한다.
 *
 * <p>{@link RefreshToken#revoke()}는 항상 {@code Instant.now()}로 폐기 시각을 고정한다(불변식 보호,
 * 임의 과거 시각 세팅 API를 의도적으로 노출하지 않음) — "7일 전에 폐기됨"을 재현하려면 저장 후
 * {@code JdbcTemplate}으로 {@code revoked_at} 컬럼을 직접 갱신해야 한다.
 */
class RefreshTokenCleanupSchedulerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RefreshTokenCleanupScheduler scheduler;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenIssuer refreshTokenIssuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User user;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.save(User.of("cleanup-target@example.com", "hash"));
    }

    @Test
    void deletesTokensExpiredBeforeGracePeriodButKeepsRecentOrActiveTokens() {
        RefreshToken longExpired = saveWithExpiry(Instant.now().minus(Duration.ofDays(8)));
        RefreshToken recentlyExpired = saveWithExpiry(Instant.now().minusSeconds(1));
        RefreshToken active = saveWithExpiry(Instant.now().plusSeconds(3600));

        scheduler.cleanup();

        assertThat(refreshTokenRepository.findById(longExpired.getId())).isEmpty();
        assertThat(refreshTokenRepository.findById(recentlyExpired.getId())).isPresent();
        assertThat(refreshTokenRepository.findById(active.getId())).isPresent();
    }

    @Test
    void deletesTokensRevokedBeforeGracePeriodButKeepsRecentlyRevokedTokens() {
        RefreshToken longRevoked = saveWithExpiry(Instant.now().plusSeconds(3600));
        revokeAt(longRevoked, Instant.now().minus(Duration.ofDays(8)));
        RefreshToken recentlyRevoked = saveWithExpiry(Instant.now().plusSeconds(3600));
        revokeAt(recentlyRevoked, Instant.now().minusSeconds(1));

        scheduler.cleanup();

        assertThat(refreshTokenRepository.findById(longRevoked.getId())).isEmpty();
        assertThat(refreshTokenRepository.findById(recentlyRevoked.getId())).isPresent();
    }

    private RefreshToken saveWithExpiry(Instant expiresAt) {
        String rawToken = UUID.randomUUID().toString();
        return refreshTokenRepository.saveAndFlush(
                RefreshToken.of(user, refreshTokenIssuer.hash(rawToken), expiresAt));
    }

    private void revokeAt(RefreshToken token, Instant revokedAt) {
        jdbcTemplate.update("UPDATE refresh_tokens SET revoked_at = ? WHERE id = ?",
                Timestamp.from(revokedAt), token.getId());
    }
}
