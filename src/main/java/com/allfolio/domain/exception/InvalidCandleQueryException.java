package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * {@code GET /v1/assets/{id}/candles}의 {@code interval}·{@code before} 쿼리 파라미터를
 * {@code CandleService}가 문자열로 직접 파싱하다 실패했을 때 던진다({@code InvalidCursorException}과
 * 동일한 패턴, docs/ROADMAP.md Task 028) — 두 파라미터 모두 같은 400 VALIDATION_ERROR로 응답하므로
 * 파라미터별 예외를 따로 만들지 않고 이 하나로 통합한다.
 */
public class InvalidCandleQueryException extends RuntimeException {

    public InvalidCandleQueryException(String message) {
        super(message);
    }
}
