package com.allfolio.web.dto;

import com.allfolio.domain.AssetType;

import java.util.UUID;

/**
 * GET /v1/portfolio의 자산별 항목. evaluationKrw/unrealizedPnl/weight는 외부 시세·환율 연동
 * (Phase 3, docs/ROADMAP.md Task 023) 전까지는 항상 null이다 — 거짓 숫자를 내보내지 않기 위해
 * 필드 자체는 미리 계약에 포함해 두고 값만 비워둔다.
 *
 * <p>weight가 BigDecimal이 아닌 String인 이유: 금액·수량·비중 등 모든 금융성 수치는 JSON에서
 * 문자열로 직렬화하는 계약이다(docs/ROADMAP.md Task 006) — BigDecimal로 두면 Jackson이 JSON
 * 숫자로 내보내 계약이 깨진다.
 */
public record PortfolioItemResponse(
        UUID assetId,
        String ticker,
        String name,
        AssetType assetType,
        String currency,
        String quantity,
        String avgPrice,
        String cost,
        String evaluationKrw,
        String unrealizedPnl,
        String weight
) {
}
