package com.allfolio.web.dto;

/**
 * 캔들 1개(Task 028). {@code bucketStart}는 이 캔들이 나타내는 구간의 시작을 문자열로 그대로
 * 실어나른다 — COIN은 {@link com.allfolio.domain.Candle#candleAt()}의 {@code Instant.toString()}
 * (UTC, 예: {@code "2026-09-11T00:00:00Z"}), STOCK은 {@link com.allfolio.domain.DailyBar#date()}의
 * {@code LocalDate.toString()}(예: {@code "2026-09-11"})이다. 두 자산유형이 시간 표현 자체가 달라
 * (COIN은 시각까지, STOCK은 날짜만) 공통 타입으로 억지로 통일하지 않는다. open/high/low/close는
 * {@link com.allfolio.domain.PrecisionScale}로 스케일을 맞춘 뒤 {@code toPlainString()}한 값이다.
 */
public record CandleBarResponse(String bucketStart, String open, String high, String low, String close) {
}
