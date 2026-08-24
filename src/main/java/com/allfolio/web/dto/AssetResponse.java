package com.allfolio.web.dto;

import com.allfolio.domain.AssetType;

import java.time.Instant;
import java.util.UUID;

/**
 * 엔티티→DTO 값 변환은 AssetService가 담당한다(open-in-view: false 환경에서 트랜잭션 밖 지연
 * 로딩을 피하기 위함, docs/ROADMAP.md Task 012). 이 레코드는 순수 값만 받는다.
 */
public record AssetResponse(
        UUID id,
        String ticker,
        String name,
        AssetType assetType,
        String currency,
        String quantity,
        String avgPrice,
        int version,
        Instant updatedAt
) {
}
