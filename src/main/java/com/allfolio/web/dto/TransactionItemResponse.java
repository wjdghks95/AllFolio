package com.allfolio.web.dto;

import com.allfolio.domain.TransactionType;

import java.time.Instant;
import java.util.UUID;

/**
 * GET /v1/assets/{id}/transactions 목록 항목. 매 항목마다 holding 전체를 실어 보낼 필요가 없어
 * TransactionResponse와 분리한다 (docs/ROADMAP.md Task 024).
 */
public record TransactionItemResponse(
        UUID id,
        TransactionType txType,
        String price,
        String quantity,
        Instant tradedAt
) {
}
