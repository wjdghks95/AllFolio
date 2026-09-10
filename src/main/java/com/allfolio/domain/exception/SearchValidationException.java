package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * 종목 검색(GET /v1/assets/search)에서 지원하지 않는 assetType/currency 조합이 들어왔을 때 던진다.
 */
public class SearchValidationException extends RuntimeException {

    public SearchValidationException(String message) {
        super(message);
    }
}
