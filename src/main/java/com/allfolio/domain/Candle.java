package com.allfolio.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 캔들스틱 차트용 OHLC 한 구간(Task 028). {@code candleAt}은 캔들 구간의 시작 시각(UTC)이다 —
 * 업비트 응답의 {@code candle_date_time_utc}(구간 시작)를 그대로 쓰며, 마지막 체결 시각인
 * {@code timestamp} 필드와는 다른 개념이므로 혼동하지 말 것(실측: days/weeks/months 응답 모두
 * {@code candle_date_time_utc}가 {@code first_day_of_period}와 일치, {@code timestamp}는 구간 내
 * 마지막 체결 시각이라 완결 캔들에서도 구간 종료 시각과 다르다).
 */
public record Candle(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Instant candleAt) {
}
