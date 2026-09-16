package com.allfolio;

import com.allfolio.domain.DevicePlatform;
import com.allfolio.domain.DeviceToken;
import com.allfolio.domain.User;
import com.allfolio.domain.repository.DeviceTokenRepository;
import com.allfolio.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/ROADMAP.md Task 029 — V4__device_tokens.sql / DeviceToken 매핑 검증.
 * Testcontainers PG18에서 실제 마이그레이션이 적용된 스키마를 대상으로 저장·조회·해지를 확인한다.
 */
class DeviceTokenRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private User other;

    @BeforeEach
    void setUp() {
        deviceTokenRepository.deleteAll();
        userRepository.deleteAll();
        owner = userRepository.save(User.of("device-owner@example.com", "hash"));
        other = userRepository.save(User.of("device-other@example.com", "hash"));
    }

    @Test
    void savedTokenIsFoundByTokenAndByOwner() {
        DeviceToken saved = deviceTokenRepository.save(
                DeviceToken.of(owner, "fcm-token-android-1", DevicePlatform.ANDROID));

        Optional<DeviceToken> byToken = deviceTokenRepository.findByToken("fcm-token-android-1");
        assertThat(byToken).isPresent();
        assertThat(byToken.get().getId()).isEqualTo(saved.getId());
        assertThat(byToken.get().getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(byToken.get().getUser().getId()).isEqualTo(owner.getId());
        assertThat(byToken.get().getCreatedAt()).isNotNull();
        assertThat(byToken.get().getRevokedAt()).isNull();

        assertThat(deviceTokenRepository.findByIdAndUser_Id(saved.getId(), owner.getId())).isPresent();
        // 타 유저 소유 토큰은 조회되지 않는다(404 컨벤션의 전제)
        assertThat(deviceTokenRepository.findByIdAndUser_Id(saved.getId(), other.getId())).isEmpty();
    }

    @Test
    void platformIsStoredAsStringNotOrdinal() {
        deviceTokenRepository.save(DeviceToken.of(owner, "fcm-token-ios-1", DevicePlatform.IOS));

        String platform = jdbcTemplate.queryForObject(
                "SELECT platform FROM device_tokens WHERE token = ?", String.class, "fcm-token-ios-1");

        assertThat(platform).isEqualTo("IOS");
    }

    @Test
    void revokeSetsRevokedAtAndExcludesTokenFromActiveLookup() {
        DeviceToken active = deviceTokenRepository.save(
                DeviceToken.of(owner, "fcm-token-web-active", DevicePlatform.WEB));
        DeviceToken toRevoke = deviceTokenRepository.save(
                DeviceToken.of(owner, "fcm-token-web-revoked", DevicePlatform.WEB));

        toRevoke.revoke();
        deviceTokenRepository.saveAndFlush(toRevoke);

        DeviceToken reloaded = deviceTokenRepository.findById(toRevoke.getId()).orElseThrow();
        assertThat(reloaded.getRevokedAt()).isNotNull();

        List<DeviceToken> activeTokens = deviceTokenRepository.findByUser_IdAndRevokedAtIsNull(owner.getId());
        assertThat(activeTokens).extracting(DeviceToken::getId).containsExactly(active.getId());
    }

    @Test
    void revokeIsIdempotentAndKeepsFirstRevokedAt() {
        DeviceToken token = deviceTokenRepository.save(
                DeviceToken.of(owner, "fcm-token-idempotent", DevicePlatform.ANDROID));

        token.revoke();
        Instant firstRevokedAt = token.getRevokedAt();
        token.revoke();

        assertThat(token.getRevokedAt()).isEqualTo(firstRevokedAt);
    }

    @Test
    void duplicateTokenViolatesUniqueConstraint() {
        deviceTokenRepository.saveAndFlush(DeviceToken.of(owner, "fcm-token-duplicate", DevicePlatform.ANDROID));

        assertThatThrownBy(() -> deviceTokenRepository.saveAndFlush(
                DeviceToken.of(other, "fcm-token-duplicate", DevicePlatform.IOS)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserCascadesToDeviceTokens() {
        deviceTokenRepository.saveAndFlush(DeviceToken.of(owner, "fcm-token-cascade", DevicePlatform.ANDROID));
        UUID ownerId = owner.getId();

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerId);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", Integer.class, ownerId);
        assertThat(remaining).isZero();
    }

    @Test
    void activeTokenPartialIndexExists() {
        String definition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'device_tokens' AND indexname = ?",
                String.class, "idx_device_tokens_active");

        assertThat(definition).contains("WHERE (revoked_at IS NULL)");
    }
}
