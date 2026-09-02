package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * CASH 자산 중 통화가 KRW인 경우 avgPrice=1 고정값 자체가 이미 평가금액이라 시세 조회 대상이
 * 아니다(docs/ROADMAP.md CASH avg_price 결정 참고).
 */
public class PriceUnavailableException extends RuntimeException {

    public PriceUnavailableException(String message) {
        super(message);
    }
}
