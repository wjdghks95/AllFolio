package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * 사용자당 종목 검색 요청 한도(Task 026, 한도 값은 SearchThrottleProperties 참조)를 초과했을 때 던진다.
 */
public class SearchRateLimitExceededException extends RuntimeException {

    public SearchRateLimitExceededException(String message) {
        super(message);
    }
}
