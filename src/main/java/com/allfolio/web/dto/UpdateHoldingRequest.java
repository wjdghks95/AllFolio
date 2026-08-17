package com.allfolio.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * PUT /v1/assets/{id}/holdings 요청. version은 낙관적 잠금 충돌 감지용 — 클라이언트가
 * 상세 조회 응답에서 읽은 값을 그대로 되돌려줘야 409 HOLDING_CONFLICT가 성립한다
 * (docs/ROADMAP.md Task 006).
 *
 * avgPrice는 대상 자산이 CASH일 수 있어 null을 허용한다. 이 DTO는 assetType을 모르므로
 * 클래스 레벨 검증은 불가능하다. 확정된 처리(docs/ROADMAP.md Task 006, Bean Validation 규칙):
 * 대상 자산이 CASH면 값을 무시하고 1을 유지, 그 외 타입에서 null이면 POST와 동일하게
 * 400 VALIDATION_ERROR. Task 012 서비스 로직에서 경로의 {id}로 조회한 자산 타입을 보고 분기한다.
 */
public record UpdateHoldingRequest(

        @NotNull
        @DecimalMin("0")
        @Digits(integer = 20, fraction = 8)
        BigDecimal quantity,

        @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 20, fraction = 8)
        BigDecimal avgPrice,

        @NotNull
        Integer version
) {
}
