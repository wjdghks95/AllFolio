package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * GET /v1/assets/{id}/transactions의 cursor 파라미터가 "<epochSecond>.<nano>_<uuid>" 형식으로
 * 파싱되지 않을 때 던진다 (docs/ROADMAP.md Task 024, code-reviewer 지적 — 파싱 실패가 500으로
 * 새던 버그).
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String message) {
        super(message);
    }
}
