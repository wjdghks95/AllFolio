package com.allfolio.web.dto;

import java.util.List;
import java.util.Map;

/**
 * GET /v1/portfolio 응답. totalCostByCurrency가 통화별 Map인 이유: 취득원가 합계를 단일
 * totalCostKrw 하나로 두면 USD 자산이 섞였을 때 환율 없이는 정확한 값을 못 낸다. Phase 3
 * 환율 연동(docs/ROADMAP.md Task 023) 전까지 거짓 숫자를 내보내지 않으려면 통화별로 나눠
 * 담는 게 유일하게 정직한 형태다. totalEvaluationKrw/totalUnrealizedPnl은 그때까지 null.
 */
public record PortfolioResponse(
        List<PortfolioItemResponse> items,
        Map<String, String> totalCostByCurrency,
        String totalEvaluationKrw,
        String totalUnrealizedPnl
) {
}
