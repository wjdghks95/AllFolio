package com.allfolio.infra.cache;

import com.allfolio.domain.DailyBar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * STOCK(국내+해외 공통) 일봉 원본 캐시 항목(Task 028). {@link #bars()}는 날짜 오름차순으로 정렬된
 * 일봉 원본이며, {@link #oldestCovered()}/{@link #newestCovered()}는 "이 범위까지는 이미 벤더에
 * 물어봤다"는 커버리지 경계다 — bars가 비어 있는 날짜(휴장일 등)가 있어도 이 범위 안이면 재조회할
 * 필요가 없다는 의미이므로 bars의 최소/최대 날짜와 반드시 일치하지는 않는다(예: 커버리지 상한
 * {@code maxHistoryStart}에 도달해 그 이전에는 더 이상 거래일 데이터가 없는 경우).
 */
public record CandleCacheEntry(List<DailyBar> bars, LocalDate oldestCovered, LocalDate newestCovered, Instant cachedAt) {
}
