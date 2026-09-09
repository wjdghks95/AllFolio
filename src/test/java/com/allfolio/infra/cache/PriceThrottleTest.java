package com.allfolio.infra.cache;

import com.allfolio.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers Redis(AbstractIntegrationTest 공유 컨테이너)로 실제 Lua 스크립트를 실행해
 * 고정 윈도우 Throttling을 검증한다(Task 022). application.yml 기본값(window=1s)을 그대로 쓴다 —
 * 짧은 window로 오버라이드하려고 @DynamicPropertySource를 추가하면 이 클래스가 다른 통합 테스트와
 * 별도 Spring 컨텍스트로 분리되어 PostgreSQL Testcontainer의 커넥션 풀을 추가로 점유하게 된다
 * (AssetPriceIntegrationTest에 남긴 것과 동일한 이유로 회피, 실측 확인됨).
 */
class PriceThrottleTest extends AbstractIntegrationTest {

    @Autowired
    private PriceThrottle priceThrottle;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void secondCallWithinSameWindowIsRejected() {
        UUID userId = UUID.randomUUID();

        assertThat(priceThrottle.tryAcquire(userId)).isTrue();
        assertThat(priceThrottle.tryAcquire(userId)).isFalse();
    }

    @Test
    void callAfterWindowElapsedIsAcceptedAgain() throws InterruptedException {
        UUID userId = UUID.randomUUID();

        assertThat(priceThrottle.tryAcquire(userId)).isTrue();
        Thread.sleep(1_100);

        assertThat(priceThrottle.tryAcquire(userId)).isTrue();
    }

    /**
     * 회귀 방지(Task 026 서브태스크 3): tryAcquire(UUID)는 내부적으로 tryAcquire(userId, "price")에
     * 위임하도록 리팩터링했다 — 리팩터링 전과 동일한 Redis 키("throttle:price:"+userId)를 써야 기존
     * 시세 조회 Throttle 동작(초당 1건)이 그대로 유지된다.
     */
    @Test
    void tryAcquireWithoutPrefixUsesPriceKeyFormatForBackwardCompatibility() {
        UUID userId = UUID.randomUUID();

        priceThrottle.tryAcquire(userId);

        assertThat(stringRedisTemplate.hasKey("throttle:price:" + userId)).isTrue();
    }

    /** 신규 keyPrefix 오버로드는 prefix별로 독립된 Redis 키 공간을 쓴다 — "price"와 카운터가 섞이지 않는다. */
    @Test
    void tryAcquireWithPrefixUsesSeparateKeySpaceFromPriceThrottle() {
        UUID userId = UUID.randomUUID();

        assertThat(priceThrottle.tryAcquire(userId, "search")).isTrue();

        assertThat(stringRedisTemplate.hasKey("throttle:search:" + userId)).isTrue();
        // "search" prefix로 쓴 호출이 "price" 카운터에 영향을 주지 않으므로, 같은 사용자의 첫 price
        // 조회는 여전히 허용돼야 한다.
        assertThat(priceThrottle.tryAcquire(userId)).isTrue();
    }

    @Test
    void secondCallWithSamePrefixWithinSameWindowIsRejected() {
        UUID userId = UUID.randomUUID();

        assertThat(priceThrottle.tryAcquire(userId, "search")).isTrue();
        assertThat(priceThrottle.tryAcquire(userId, "search")).isFalse();
    }
}
