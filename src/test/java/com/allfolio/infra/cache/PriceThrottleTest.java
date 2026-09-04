package com.allfolio.infra.cache;

import com.allfolio.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
}
