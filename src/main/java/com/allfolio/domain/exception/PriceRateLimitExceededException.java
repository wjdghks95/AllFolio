package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * 캐시가 신선하지 않아 외부 시세 API를 실제로 호출하려는 시점에 사용자당 요청 한도(Task 022)를
 * 초과했을 때 던진다. 캐시 히트로 외부 API를 부르지 않는 요청에는 적용되지 않는다.
 */
public class PriceRateLimitExceededException extends RuntimeException {

    public PriceRateLimitExceededException(String message) {
        super(message);
    }
}
