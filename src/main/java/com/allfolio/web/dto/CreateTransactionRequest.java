package com.allfolio.web.dto;

import com.allfolio.domain.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * POST /v1/assets/{id}/transactions 요청. price는 대상 자산이 CASH일 수 있어 null을 허용한다
 * (UpdateHoldingRequest.avgPrice와 동일 패턴) — 이 DTO는 assetType을 모르므로 클래스 레벨 검증은
 * 불가능하다. CASH면 서비스에서 1로 대체, 그 외 타입에서 null이면 400 VALIDATION_ERROR
 * (AvgPriceRequiredException, docs/ROADMAP.md Task 024).
 */
public record CreateTransactionRequest(

        @NotNull
        TransactionType txType,

        @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 20, fraction = 8)
        BigDecimal price,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 20, fraction = 8)
        BigDecimal quantity,

        @NotNull
        Instant tradedAt
) {
}
