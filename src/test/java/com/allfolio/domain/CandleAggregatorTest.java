package com.allfolio.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 028 「캔들 캐시·집계·페이징 인프라」 — STOCK(국내+해외 공통) 일봉→주/월/년봉
 * 집계. {@link CandleAggregator}는 외부 의존 없는 순수 함수라 Spring 컨텍스트 없이 검증한다
 * ({@code UpbitCandleAggregationTest}와 동일한 커버리지 원칙: 정상 구간·부분 구간 경계값·복수 구간
 * 그룹핑·입력 순서 무관성).
 */
class CandleAggregatorTest {

    private static DailyBar bar(LocalDate date, String open, String high, String low, String close) {
        return new DailyBar(date, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close));
    }

    // ===== aggregateWeeks (월요일 시작 ISO-8601 주) =====

    @Test
    void aggregatesFullWeekOpenFromMondayCloseFromFriday() {
        // 2026-09-07(월) ~ 2026-09-11(금)
        List<DailyBar> daily = List.of(
                bar(LocalDate.of(2026, 9, 7), "100", "110", "95", "105"),
                bar(LocalDate.of(2026, 9, 9), "105", "130", "100", "120"),
                bar(LocalDate.of(2026, 9, 11), "120", "125", "115", "118"));

        List<DailyBar> weeks = CandleAggregator.aggregateWeeks(daily);

        assertThat(weeks).hasSize(1);
        DailyBar week = weeks.get(0);
        assertThat(week.date()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(week.open()).isEqualByComparingTo("100");
        assertThat(week.close()).isEqualByComparingTo("118");
        assertThat(week.high()).isEqualByComparingTo("130");
        assertThat(week.low()).isEqualByComparingTo("95");
    }

    /**
     * 경계값: 주 초반(월~수)만 데이터가 있는 부분 주도 예외 없이 "주어진 날만으로" 집계해야 한다 —
     * date는 실제로 존재하지 않는 목~금을 기다리지 않고 그 주의 월요일로 고정된다.
     */
    @Test
    void aggregatesPartialWeekUsingOnlyAvailableDays() {
        List<DailyBar> daily = List.of(
                bar(LocalDate.of(2026, 9, 7), "200", "210", "195", "205"),
                bar(LocalDate.of(2026, 9, 9), "205", "230", "190", "220"));

        List<DailyBar> weeks = CandleAggregator.aggregateWeeks(daily);

        assertThat(weeks).hasSize(1);
        DailyBar week = weeks.get(0);
        assertThat(week.date()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(week.open()).isEqualByComparingTo("200");
        assertThat(week.close()).isEqualByComparingTo("220");
        assertThat(week.high()).isEqualByComparingTo("230");
        assertThat(week.low()).isEqualByComparingTo("190");
    }

    @Test
    void groupsMultipleWeeksIndependentlyAndSortsMostRecentFirst() {
        List<DailyBar> daily = List.of(
                bar(LocalDate.of(2026, 8, 31), "80", "90", "70", "85"), // 이전 주 월요일
                bar(LocalDate.of(2026, 9, 7), "85", "95", "80", "90"),
                bar(LocalDate.of(2026, 9, 8), "90", "100", "85", "95"));

        List<DailyBar> weeks = CandleAggregator.aggregateWeeks(daily);

        assertThat(weeks).hasSize(2);
        assertThat(weeks.get(0).date()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(weeks.get(0).open()).isEqualByComparingTo("85");
        assertThat(weeks.get(0).close()).isEqualByComparingTo("95");
        assertThat(weeks.get(1).date()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(weeks.get(1).open()).isEqualByComparingTo("80");
        assertThat(weeks.get(1).close()).isEqualByComparingTo("85");
    }

    @Test
    void weekInputOrderDoesNotAffectResult() {
        List<DailyBar> daily = List.of(
                bar(LocalDate.of(2026, 9, 11), "120", "125", "115", "118"),
                bar(LocalDate.of(2026, 9, 7), "100", "110", "95", "105"),
                bar(LocalDate.of(2026, 9, 9), "105", "130", "100", "120"));

        List<DailyBar> weeks = CandleAggregator.aggregateWeeks(daily);

        assertThat(weeks).hasSize(1);
        assertThat(weeks.get(0).open()).isEqualByComparingTo("100");
        assertThat(weeks.get(0).close()).isEqualByComparingTo("118");
    }

    // ===== aggregateMonths =====

    @Test
    void aggregatesFullMonthOpenFromFirstDayCloseFromLastDay() {
        List<DailyBar> daily = List.of(
                bar(LocalDate.of(2026, 9, 1), "1000", "1050", "980", "1020"),
                bar(LocalDate.of(2026, 9, 15), "1020", "1100", "1000", "1080"),
                bar(LocalDate.of(2026, 9, 30), "1080", "1090", "1040", "1060"));

        List<DailyBar> months = CandleAggregator.aggregateMonths(daily);

        assertThat(months).hasSize(1);
        DailyBar month = months.get(0);
        assertThat(month.date()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(month.open()).isEqualByComparingTo("1000");
        assertThat(month.close()).isEqualByComparingTo("1060");
        assertThat(month.high()).isEqualByComparingTo("1100");
        assertThat(month.low()).isEqualByComparingTo("980");
    }

    /** 경계값: 월 중순부터만 데이터가 있는 부분 월(상장·상폐 등)도 그 데이터만으로 집계한다. */
    @Test
    void aggregatesPartialMonthUsingOnlyAvailableDays() {
        List<DailyBar> daily = List.of(
                bar(LocalDate.of(2026, 9, 20), "500", "520", "490", "510"),
                bar(LocalDate.of(2026, 9, 28), "510", "540", "495", "530"));

        List<DailyBar> months = CandleAggregator.aggregateMonths(daily);

        assertThat(months).hasSize(1);
        DailyBar month = months.get(0);
        assertThat(month.date()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(month.open()).isEqualByComparingTo("500");
        assertThat(month.close()).isEqualByComparingTo("530");
        assertThat(month.high()).isEqualByComparingTo("540");
        assertThat(month.low()).isEqualByComparingTo("490");
    }

    // ===== aggregateYears =====

    @Test
    void aggregatesFullYearOpenFromJanuaryCloseFromDecember() {
        List<DailyBar> daily = List.of(
                bar(LocalDate.of(2025, 1, 2), "100", "120", "90", "110"),
                bar(LocalDate.of(2025, 6, 15), "110", "150", "100", "140"),
                bar(LocalDate.of(2025, 12, 30), "140", "160", "130", "155"));

        List<DailyBar> years = CandleAggregator.aggregateYears(daily);

        assertThat(years).hasSize(1);
        DailyBar year = years.get(0);
        assertThat(year.date()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(year.open()).isEqualByComparingTo("100");
        assertThat(year.close()).isEqualByComparingTo("155");
        assertThat(year.high()).isEqualByComparingTo("160");
        assertThat(year.low()).isEqualByComparingTo("90");
    }

    /** 경계값: 상장 첫해처럼 연도 중반부터만 데이터가 있는 부분 연도도 그 데이터만으로 집계한다. */
    @Test
    void aggregatesPartialYearUsingOnlyAvailableMonths() {
        List<DailyBar> daily = List.of(
                bar(LocalDate.of(2025, 3, 3), "200", "210", "195", "205"),
                bar(LocalDate.of(2025, 11, 20), "205", "230", "190", "220"));

        List<DailyBar> years = CandleAggregator.aggregateYears(daily);

        assertThat(years).hasSize(1);
        DailyBar year = years.get(0);
        assertThat(year.date()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(year.open()).isEqualByComparingTo("200");
        assertThat(year.close()).isEqualByComparingTo("220");
        assertThat(year.high()).isEqualByComparingTo("230");
        assertThat(year.low()).isEqualByComparingTo("190");
    }
}
