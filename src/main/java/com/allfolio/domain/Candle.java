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

    /**
     * 값 기준 동등성 비교(스케일에 안전) — record 기본 {@link #equals}는 {@code BigDecimal.equals}를
     * 써서 값이 같아도 스케일이 다르면(예: {@code "100"}과 {@code "100.0"}) 다르다고 판정한다
     * ({@code .claude/rules/testing.md} "BigDecimal 비교는 .compareTo() 사용" 규칙).
     * {@code CandlePushScheduler}가 "직전에 보낸 캔들과 값이 같은지"를 판정할 때 기본 equals 대신
     * 이 메서드를 쓴다(Task 028 code-reviewer m1 실측 수정).
     */
    public boolean valueEquals(Candle other) {
        if (other == null) {
            return false;
        }
        return open.compareTo(other.open) == 0
                && high.compareTo(other.high) == 0
                && low.compareTo(other.low) == 0
                && close.compareTo(other.close) == 0
                && candleAt.equals(other.candleAt);
    }
}
