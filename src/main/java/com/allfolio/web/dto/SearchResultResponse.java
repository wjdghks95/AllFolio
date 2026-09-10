package com.allfolio.web.dto;

import com.allfolio.domain.SearchResult;

/**
 * GET /v1/assets/search 응답 항목(docs/ROADMAP.md Task 026). 금액 필드를 포함하지 않는다 —
 * 검색 결과 건수만큼 시세 API를 호출하면 무료 한도가 소진되기 때문이다.
 */
public record SearchResultResponse(String ticker, String name, String assetType, String currency) {

    public static SearchResultResponse from(SearchResult r) {
        return new SearchResultResponse(r.ticker(), r.name(), r.assetType().name(), r.currency());
    }
}
