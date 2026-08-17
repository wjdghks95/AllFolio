package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * 자산이 없을 때와 남의 자산일 때 모두 이 예외를 던진다(403이 아닌 404) — ID 존재 여부가
 * 새어 나가지 않도록 하기 위함이다 (docs/ROADMAP.md Task 012, shrimp-rules.md).
 */
public class AssetNotFoundException extends RuntimeException {

    public AssetNotFoundException(String message) {
        super(message);
    }
}
