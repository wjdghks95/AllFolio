package com.allfolio.web.dto;

import java.util.List;
import java.util.Map;

/**
 * GET /v1/portfolio 응답. totalCostByCurrency가 통화별 Map인 이유: 취득원가 합계를 단일
 * totalCostKrw 하나로 두면 USD 자산이 섞였을 때 환율 없이는 정확한 값을 못 낸다 — 통화별로 나눠
 * 담는 게 유일하게 정직한 형태다(원가는 KRW 환산하지 않는다). totalEvaluationKrw/totalUnrealizedPnl은
 * 시세 조회에 성공한 자산들만으로 계산한 KRW 환산 합계다(docs/ROADMAP.md Task 023) — 성공한 자산이
 * 하나도 없으면 null이다.
 */
public record PortfolioResponse(
        List<PortfolioItemResponse> items,
        Map<String, String> totalCostByCurrency,
        String totalEvaluationKrw,
        String totalUnrealizedPnl
) {
}
