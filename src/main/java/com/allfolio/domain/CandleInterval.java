package com.allfolio.domain;

import com.allfolio.domain.exception.InvalidCandleQueryException;

import java.util.Locale;

/**
 * 캔들 차트 조회 단위(Task 028 「CandleService + REST 캔들 엔드포인트」). {@code GET
 * /v1/assets/{id}/candles}의 {@code interval} 쿼리 파라미터는 Spring의 자동 enum 바인딩을 쓰지 않고
 * 문자열로 받아 {@code CandleService}가 직접 파싱한다 — 자동 바인딩 실패
 * ({@code MethodArgumentTypeMismatchException})는 이 프로젝트의 {@code GlobalExceptionHandler}
 * 매핑 체계 밖으로 새 500이 된다(web/CLAUDE.md 「컨트롤러 @Validated 금지」와 같은 계열의 함정).
 *
 * <p>분봉(MINUTE1~MINUTE240)은 COIN 전용이다 — 업비트가 지원하는 분봉 unit(1, 3, 5, 10, 15, 30, 60,
 * 240, {@code UpbitPriceClient#getMinuteCandles} Javadoc 실측 확인)을 그대로 enum 상수 이름에 반영해,
 * 쿼리 파라미터 {@code interval=minute1}이 {@code valueOf("MINUTE1")}에 곧장 매칭되게 한다. STOCK은
 * 공공데이터포털·Twelve Data 둘 다 일봉 미만 단위를 벤더가 제공하지 않아 분봉을 지원하지 않는다
 * ({@code CandleService#getCandles}에서 STOCK+분봉 조합을 400으로 거른다).
 */
public enum CandleInterval {
    MINUTE1(1), MINUTE3(3), MINUTE5(5), MINUTE10(10), MINUTE15(15), MINUTE30(30), MINUTE60(60), MINUTE240(240),
    DAY(null), WEEK(null), MONTH(null), YEAR(null);

    private final Integer minuteUnit;

    CandleInterval(Integer minuteUnit) {
        this.minuteUnit = minuteUnit;
    }

    /** COIN 전용 분봉 여부 — STOCK 경로 진입 차단(400)과 STOCK 쪽 switch 방어 분기에 쓰인다. */
    public boolean isMinute() {
        return minuteUnit != null;
    }

    /** 업비트 {@code /v1/candles/minutes/{unit}}에 그대로 넘길 unit 값. {@link #isMinute()}가 false면 호출 금지. */
    public int minuteUnit() {
        if (minuteUnit == null) {
            throw new IllegalStateException(this + "은 분봉 interval이 아닙니다.");
        }
        return minuteUnit;
    }

    /**
     * 쿼리 파라미터 문자열(대소문자 무관)을 파싱한다. {@code CandleService.getCandles}와
     * {@code AssetController}의 SSE 스트리밍 엔드포인트(Task 028)가 공유하는 유일한 interval 파싱
     * 진입점 — 두 곳이 각자 파싱 로직을 새로 짜면 규칙이 어긋날 수 있어 이 정적 팩토리로 통합한다.
     */
    public static CandleInterval from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidCandleQueryException("interval 파라미터는 필수입니다.");
        }
        try {
            return CandleInterval.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidCandleQueryException("지원하지 않는 interval 값입니다: " + raw);
        }
    }
}
