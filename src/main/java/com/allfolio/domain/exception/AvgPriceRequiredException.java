package com.allfolio.domain.exception;

/**
 * HTTP 매핑은 GlobalExceptionHandler 한 곳에서만 한다 — @ResponseStatus를 붙이지 않는다.
 * PUT /v1/assets/{id}/holdings 대상 자산이 CASH가 아닌데 avgPrice가 null일 때 던진다.
 * CreateAssetRequest는 AvgPriceRequiredUnlessCash 클래스 레벨 제약으로 같은 규칙을 검증하지만,
 * UpdateHoldingRequest는 assetType을 모르는 채로 와서 클래스 레벨 제약으로 표현할 수 없어
 * 서비스 로직에서 이 예외로 같은 검증을 수행한다 (docs/ROADMAP.md Task 012).
 */
public class AvgPriceRequiredException extends RuntimeException {

    public AvgPriceRequiredException(String message) {
        super(message);
    }
}
