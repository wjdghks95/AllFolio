package com.allfolio.web.dto;

import com.allfolio.domain.TransactionType;

import java.time.Instant;
import java.util.UUID;

/**
 * POST /v1/assets/{id}/transactions 응답. 거래 입력 직후 갱신된 잔고를 다시 조회하지 않아도 되도록
 * holding을 AssetResponse 그대로 포함한다 (docs/ROADMAP.md Task 024).
 */
public record TransactionResponse(
        UUID id,
        TransactionType txType,
        String price,
        String quantity,
        Instant tradedAt,
        AssetResponse holding
) {
}
