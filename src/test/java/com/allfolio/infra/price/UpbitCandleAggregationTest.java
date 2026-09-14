package com.allfolio.infra.price;

import com.allfolio.domain.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 028 「COIN 캔들 조회 클라이언트 확장」 — 업비트가 년봉 엔드포인트를
 * 제공하지 않아 월봉을 연 단위로 직접 집계하는 {@link UpbitPriceClient#aggregateYears}는 외부
 * 의존(RestClient·CircuitBreaker) 없는 순수 함수라 Spring 컨텍스트 없이 검증한다.
 */
class UpbitCandleAggregationTest {

    private static Candle month(int year, int month, String open, String high, String low, String close) {
        Instant candleAt = LocalDateTime.of(year, month, 1, 0, 0).toInstant(ZoneOffset.UTC);
        return new Candle(new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), candleAt);
    }

    @Test
    void aggregatesFullYearOpenFromJanuaryAndCloseFromDecember() {
        List<Candle> months = List.of(
                month(2025, 1, "100", "120", "90", "110"),
                month(2025, 6, "110", "150", "100", "140"),
                month(2025, 12, "140", "160", "130", "155"));

        List<Candle> years = UpbitPriceClient.aggregateYears(months);

        assertThat(years).hasSize(1);
        Candle year2025 = years.get(0);
        assertThat(year2025.open()).isEqualByComparingTo("100");
        assertThat(year2025.close()).isEqualByComparingTo("155");
        assertThat(year2025.high()).isEqualByComparingTo("160");
        assertThat(year2025.low()).isEqualByComparingTo("90");
        assertThat(year2025.candleAt()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0).toInstant(ZoneOffset.UTC));
    }

    /**
     * 경계값: 1월치가 없는 부분 연도(3월~11월만 존재)도 예외 없이 "주어진 달만으로" 집계해야 한다 —
     * open은 실제로 가장 이른 3월의 open이지, 없는 1월을 기다리거나 비워두지 않는다.
     */
    @Test
    void aggregatesPartialYearUsingOnlyAvailableMonths() {
        List<Candle> months = List.of(
                month(2025, 3, "200", "210", "195", "205"),
                month(2025, 11, "205", "230", "190", "220"));

        List<Candle> years = UpbitPriceClient.aggregateYears(months);

        assertThat(years).hasSize(1);
        Candle year2025 = years.get(0);
        assertThat(year2025.open()).isEqualByComparingTo("200");
        assertThat(year2025.close()).isEqualByComparingTo("220");
        assertThat(year2025.high()).isEqualByComparingTo("230");
        assertThat(year2025.low()).isEqualByComparingTo("190");
        // 부분 연도라도 candleAt은 실제 데이터의 시작월(3월)이 아니라 해당 연도 1월 1일로 고정한다.
        assertThat(year2025.candleAt()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0).toInstant(ZoneOffset.UTC));
    }

    @Test
    void groupsMultipleYearsIndependentlyAndSortsMostRecentFirst() {
        List<Candle> months = List.of(
                month(2024, 12, "80", "90", "70", "85"),
                month(2025, 1, "85", "95", "80", "90"),
                month(2025, 2, "90", "100", "85", "95"));

        List<Candle> years = UpbitPriceClient.aggregateYears(months);

        assertThat(years).hasSize(2);
        assertThat(years.get(0).candleAt()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0).toInstant(ZoneOffset.UTC));
        assertThat(years.get(0).open()).isEqualByComparingTo("85");
        assertThat(years.get(0).close()).isEqualByComparingTo("95");
        assertThat(years.get(1).candleAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0).toInstant(ZoneOffset.UTC));
        assertThat(years.get(1).open()).isEqualByComparingTo("80");
        assertThat(years.get(1).close()).isEqualByComparingTo("85");
    }

    @Test
    void inputOrderDoesNotAffectResultBecauseMonthsAreSortedInternally() {
        // 업비트 응답은 최신순(내림차순)으로 오므로 입력이 시간 역순이어도 open/close가 뒤바뀌면 안 된다.
        List<Candle> monthsNewestFirst = List.of(
                month(2025, 12, "140", "160", "130", "155"),
                month(2025, 6, "110", "150", "100", "140"),
                month(2025, 1, "100", "120", "90", "110"));

        List<Candle> years = UpbitPriceClient.aggregateYears(monthsNewestFirst);

        assertThat(years).hasSize(1);
        assertThat(years.get(0).open()).isEqualByComparingTo("100");
        assertThat(years.get(0).close()).isEqualByComparingTo("155");
    }
}
