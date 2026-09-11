package com.allfolio.web.dto;

import java.util.List;

/**
 * {@code GET /v1/assets/{id}/candles} 응답(Task 028). {@code bars}는 최신순(내림차순) 정렬이다 —
 * {@link com.allfolio.domain.CandleAggregator}·{@code UpbitPriceClient.aggregateYears}·
 * 업비트 캔들 API 원본 모두 이미 최신순으로 오거나 그렇게 집계하므로 그대로 따른다.
 *
 * <p>{@code hasMoreHistory}는 {@code before} 커서로 이 응답의 가장 오래된 캔들보다 더 과거를
 * 추가로 조회할 수 있는지를 나타낸다. STOCK은 캐시 커버리지 상한
 * ({@code CandleCacheProperties.maxHistoryYears})에 도달했는지로 판정하고, COIN은 캐시가 없어
 * (이번 태스크 범위에서 COIN은 패스스루) 요청한 페이지 크기를 꽉 채워 받았는지로 근사 판정한다 —
 * 페이지가 가득 찼으면 더 과거 데이터가 남아있을 가능성이 높고, 덜 찼으면 그 마켓의 상장 이후
 * 전체 이력을 이미 다 받은 것이다(업비트는 총 이력 길이를 알려주는 별도 필드가 없다).
 */
public record CandleSeriesResponse(List<CandleBarResponse> bars, boolean hasMoreHistory) {
}
