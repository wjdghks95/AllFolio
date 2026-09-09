package com.allfolio.domain;

/**
 * 통합 종목 검색(GET /v1/assets/search) 결과 항목. STOCK+KRW(공공데이터포털)·STOCK+USD(Twelve Data)·
 * COIN(업비트) 3개 외부 소스 클라이언트가 공유하는 타입이다 — 필드 순서·타입을 임의로 바꾸지 말 것.
 * 가격은 포함하지 않는다(검색 한 번으로 여러 결과 각각의 시세를 조회하면 무료 API 한도를 소진하므로,
 * 가격 조회는 자산 등록 후 기존 GET /v1/assets/{id}/price가 담당한다).
 */
public record SearchResult(String ticker, String name, AssetType assetType, String currency) {
}
