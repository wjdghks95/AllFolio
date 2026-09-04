package com.allfolio.infra.cache;

import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.Price;
import com.allfolio.domain.PricedQuote;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers Redis(AbstractIntegrationTest 공유 컨테이너)로 실제 Redis에 저장·조회해
 * fresh/stale 판정과 BigDecimal 정밀도 보존을 검증한다(Task 022).
 */
class PriceCacheStoreTest extends AbstractIntegrationTest {

    @Autowired
    private PriceCacheStore priceCacheStore;

    @Test
    void findReturnsFreshQuoteRightAfterSave() {
        String key = "price:TEST:" + UUID.randomUUID();
        Price price = new Price(new BigDecimal("123456789.12345678"), "KRW", Instant.now());

        priceCacheStore.save(key, price);
        Optional<PricedQuote> lookup = priceCacheStore.find(key, Duration.ofMinutes(1));

        assertThat(lookup).isPresent();
        assertThat(lookup.get().stale()).isFalse();
        assertThat(lookup.get().price().amount()).isEqualByComparingTo("123456789.12345678");
        // isEqualByComparingTo는 값만 보고 scale은 무시하므로, Redis 왕복 후 scale(자릿수) 자체가
        // 보존됐는지는 별도로 확인해야 "정밀도 보존"을 검증했다고 말할 수 있다.
        assertThat(lookup.get().price().amount().scale()).isEqualTo(8);
        assertThat(lookup.get().price().currency()).isEqualTo("KRW");
    }

    /** freshTtl을 1ms로 두고 20ms를 재워, 실제 시간 경과로 stale 판정 경로를 재현한다. */
    @Test
    void findReturnsStaleQuoteWhenFreshTtlHasElapsed() throws InterruptedException {
        String key = "price:TEST:" + UUID.randomUUID();
        Price price = new Price(new BigDecimal("100"), "KRW", Instant.now());
        priceCacheStore.save(key, price);
        Thread.sleep(20);

        Optional<PricedQuote> lookup = priceCacheStore.find(key, Duration.ofMillis(1));

        assertThat(lookup).isPresent();
        assertThat(lookup.get().stale()).isTrue();
        assertThat(lookup.get().price().amount()).isEqualByComparingTo("100");
    }

    @Test
    void findReturnsEmptyForUnknownKey() {
        Optional<PricedQuote> lookup = priceCacheStore.find("price:TEST:" + UUID.randomUUID(), Duration.ofMinutes(1));

        assertThat(lookup).isEmpty();
    }

    /** 부정 캐싱(Task 023 Major 2) — markFailed() 직후에는 같은 키에 대해 hasRecentFailure()가 true. */
    @Test
    void hasRecentFailureReturnsTrueRightAfterMarkFailed() {
        String key = "price:TEST:" + UUID.randomUUID();

        priceCacheStore.markFailed(key);

        assertThat(priceCacheStore.hasRecentFailure(key)).isTrue();
    }

    @Test
    void hasRecentFailureReturnsFalseForUnknownKey() {
        assertThat(priceCacheStore.hasRecentFailure("price:TEST:" + UUID.randomUUID())).isFalse();
    }
}
