package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * 거래 입력 시 SELL 수량이 현재 보유 수량을 초과할 때 던진다 (docs/ROADMAP.md Task 024).
 */
public class InsufficientHoldingQuantityException extends RuntimeException {

    public InsufficientHoldingQuantityException(String message) {
        super(message);
    }
}
