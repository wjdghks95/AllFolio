package com.allfolio.infra.cache;

import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.DailyBar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers Redis(AbstractIntegrationTest 공유 컨테이너)로 실제 Redis에 저장·조회해 STOCK 일봉
 * 원본 캐시(find/save)와 mergeOlder(순수 함수, Redis I/O 없음)를 검증한다(Task 028).
 */
class CandleCacheStoreTest extends AbstractIntegrationTest {

    @Autowired
    private CandleCacheStore candleCacheStore;

    private static DailyBar bar(LocalDate date, String close) {
        return new DailyBar(date, new BigDecimal(close), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close));
    }

    @Test
    void findReturnsSavedEntry() {
        String key = "candles:TEST:" + UUID.randomUUID();
        List<DailyBar> bars = List.of(bar(LocalDate.of(2026, 9, 8), "100"), bar(LocalDate.of(2026, 9, 9), "105"));
        CandleCacheEntry entry = new CandleCacheEntry(
                bars, LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9), Instant.now());

        candleCacheStore.save(key, entry, Duration.ofMinutes(1));
        Optional<CandleCacheEntry> found = candleCacheStore.find(key);

        assertThat(found).isPresent();
        assertThat(found.get().bars()).hasSize(2);
        assertThat(found.get().bars().get(0).close()).isEqualByComparingTo("100");
        assertThat(found.get().oldestCovered()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(found.get().newestCovered()).isEqualTo(LocalDate.of(2026, 9, 9));
    }

    @Test
    void findReturnsEmptyForUnknownKey() {
        assertThat(candleCacheStore.find("candles:TEST:" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void mergeOlderKeepsExistingBarOnDateConflict() {
        // existing에 있는 9/8 데이터(close=100)가 "신뢰할 수 있는 이전 병합 결과"이므로, olderBars의
        // 같은 날짜(close=999, 조작된 다른 값)로 덮어써지면 안 된다.
        CandleCacheEntry existing = new CandleCacheEntry(
                List.of(bar(LocalDate.of(2026, 9, 8), "100")),
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 8), Instant.now());
        List<DailyBar> olderBars = List.of(
                bar(LocalDate.of(2026, 9, 8), "999"), bar(LocalDate.of(2026, 9, 5), "90"));

        CandleCacheEntry merged = candleCacheStore.mergeOlder(existing, olderBars, LocalDate.of(2020, 1, 1));

        assertThat(merged.bars()).hasSize(2);
        assertThat(merged.bars().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(merged.bars().get(0).close()).isEqualByComparingTo("90");
        assertThat(merged.bars().get(1).date()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(merged.bars().get(1).close()).isEqualByComparingTo("100");
        assertThat(merged.oldestCovered()).isEqualTo(LocalDate.of(2026, 9, 5));
        // newestCovered는 mergeOlder(과거 확장 전용)의 책임이 아니므로 existing 값을 그대로 유지한다.
        assertThat(merged.newestCovered()).isEqualTo(LocalDate.of(2026, 9, 8));
    }

    @Test
    void mergeOlderIsIdempotentWhenAppliedTwiceWithSameOlderBars() {
        CandleCacheEntry existing = new CandleCacheEntry(
                List.of(bar(LocalDate.of(2026, 9, 8), "100")),
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 8), Instant.now());
        List<DailyBar> olderBars = List.of(bar(LocalDate.of(2026, 9, 5), "90"), bar(LocalDate.of(2026, 9, 6), "95"));
        LocalDate cap = LocalDate.of(2020, 1, 1);

        CandleCacheEntry firstMerge = candleCacheStore.mergeOlder(existing, olderBars, cap);
        CandleCacheEntry secondMerge = candleCacheStore.mergeOlder(firstMerge, olderBars, cap);

        assertThat(secondMerge.bars()).isEqualTo(firstMerge.bars());
        assertThat(secondMerge.oldestCovered()).isEqualTo(firstMerge.oldestCovered());
        assertThat(secondMerge.newestCovered()).isEqualTo(firstMerge.newestCovered());
    }

    /** 총 범위 상한(maxHistoryStart) 도달 시 그 이전 날짜로는 확장을 거부하고, oldestCovered가 상한으로 수렴한다. */
    @Test
    void mergeOlderRejectsExpansionBeyondMaxHistoryStart() {
        CandleCacheEntry existing = new CandleCacheEntry(
                List.of(bar(LocalDate.of(2026, 3, 10), "100")),
                LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10), Instant.now());
        List<DailyBar> olderBars = List.of(
                bar(LocalDate.of(2026, 3, 4), "90"), // cap보다 과거 — 제외되어야 함
                bar(LocalDate.of(2026, 1, 1), "50")); // cap보다 훨씬 과거 — 제외되어야 함
        LocalDate cap = LocalDate.of(2026, 3, 5);

        CandleCacheEntry merged = candleCacheStore.mergeOlder(existing, olderBars, cap);

        assertThat(merged.bars()).hasSize(1);
        assertThat(merged.bars().get(0).date()).isEqualTo(LocalDate.of(2026, 3, 10));
        // 상한 이전 데이터가 전부 잘려나가 실제 남은 데이터가 없어도, 커버리지 자체는 상한까지로 확정한다.
        assertThat(merged.oldestCovered()).isEqualTo(cap);

        // 같은 요청을 반복해도(cap 도달 후 추가 확장 시도) 더 이상 과거로 넓어지지 않는다.
        CandleCacheEntry mergedAgain = candleCacheStore.mergeOlder(merged, olderBars, cap);
        assertThat(mergedAgain.bars()).isEqualTo(merged.bars());
        assertThat(mergedAgain.oldestCovered()).isEqualTo(cap);
    }
}
