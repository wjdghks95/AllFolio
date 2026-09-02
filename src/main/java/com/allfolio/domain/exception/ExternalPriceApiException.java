package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * 캐시(Redis, 후속 Task)가 아직 없어 장애 시 직전 시세를 대신 보여줄 수 없다 — 외부 시세 API
 * (업비트/KIS/환율) 호출이 최종 실패했거나 Circuit Breaker가 Open 상태일 때 장애를 명확한
 * 에러로만 알린다.
 */
public class ExternalPriceApiException extends RuntimeException {

    public ExternalPriceApiException(String message) {
        super(message);
    }

    public ExternalPriceApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
