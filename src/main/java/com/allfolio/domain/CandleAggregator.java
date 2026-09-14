package com.allfolio.domain;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * STOCK(국내+해외 공통) 일봉({@link DailyBar}) → 주/월/년봉 집계(Task 028 「캔들 캐시·집계·페이징
 * 인프라」). {@link com.allfolio.infra.price.UpbitPriceClient#aggregateYears}(COIN 전용, 월봉→년봉)와
 * 그룹핑 원칙(구간 내 open=가장 이른 날짜의 open, close=가장 늦은 날짜의 close, high/low=구간
 * max/min, 부분 구간도 그 데이터만으로 예외 없이 집계, 최신순 정렬)은 그대로 따르되 {@link DailyBar}
 * (LocalDate 기반)를 다룬다는 점이 다르다. Spring 빈이 아닌 순수 static 유틸이다 —
 * {@code infra/price/CLAUDE.md}의 "얕은 추상화 회피" 원칙에 따라 COIN의 {@code aggregateYears}와
 * 억지로 공통 인터페이스로 묶지 않는다.
 *
 * <p><b>주 경계 정의(월요일 시작 ISO-8601 주):</b> {@link DailyBar#date()}는 이미 각 벤더가 확정해
 * 내려준 거래소 기준 캘린더 날짜다(국내는 공공데이터포털 {@code basDt}=KST 기준일, 해외는 Twelve Data
 * {@code datetime}=거래소 현지 날짜). 이 프로젝트는 시각을 다룰 때 타임존을 항상 명시적으로 고정하는
 * 컨벤션이 있지만({@code StockPriceClient}의 {@code KST} 상수·{@code toAsOf()} 패턴 참고), 이 값은
 * 이미 zone-less한 확정 캘린더 날짜라는 점이 다르다 — 이미 정해진 거래일 날짜에 KST 등 특정 존을
 * 다시 얹어 재해석하면 오히려 날짜가 하루 밀리는 왜곡이 생긴다(예: LocalDate를 시작 시각 Instant로
 * 오인 변환). 그래서 이 집계 함수는 날짜 값 자체를 그대로 신뢰하고 추가 존 변환 없이 ISO-8601 주
 * (한 주의 시작은 항상 {@link DayOfWeek#MONDAY})를 적용한다 — 그 주의 월요일 날짜를 그룹 키로 쓴다.
 */
public final class CandleAggregator {

    private CandleAggregator() {
    }

    public static List<DailyBar> aggregateWeeks(List<DailyBar> daily) {
        return aggregate(daily, date -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
    }

    public static List<DailyBar> aggregateMonths(List<DailyBar> daily) {
        return aggregate(daily, date -> date.withDayOfMonth(1));
    }

    public static List<DailyBar> aggregateYears(List<DailyBar> daily) {
        return aggregate(daily, date -> LocalDate.of(date.getYear(), 1, 1));
    }

    private static List<DailyBar> aggregate(List<DailyBar> daily, java.util.function.Function<LocalDate, LocalDate> periodStartOf) {
        return daily.stream()
                .collect(Collectors.groupingBy(bar -> periodStartOf.apply(bar.date())))
                .entrySet().stream()
                .map(entry -> aggregatePeriod(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(DailyBar::date).reversed())
                .toList();
    }

    // 구간 내 open/close는 실제 보유 데이터 중 가장 이른/늦은 날짜 기준이지만, 반환하는 DailyBar 자신의
    // date는(부분 구간이라도) 항상 그 구간의 시작일(월요일/1일/1월 1일)로 고정한다 — "이 캔들이 어느
    // 구간을 나타내는지"를 명확히 하기 위함이며 COIN aggregateYears와 동일한 판단이다.
    private static DailyBar aggregatePeriod(LocalDate periodStart, List<DailyBar> barsInPeriod) {
        List<DailyBar> sorted = barsInPeriod.stream().sorted(Comparator.comparing(DailyBar::date)).toList();
        BigDecimal open = sorted.get(0).open();
        BigDecimal close = sorted.get(sorted.size() - 1).close();
        BigDecimal high = sorted.stream().map(DailyBar::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal low = sorted.stream().map(DailyBar::low).min(BigDecimal::compareTo).orElseThrow();
        return new DailyBar(periodStart, open, high, low, close);
    }
}
