package com.allfolio.domain;

/**
 * PriceService가 캐시 판정 결과를 함께 실어 나르기 위한 값 객체(Task 022). stale=true는 외부 API
 * 장애로 fresh 캐시 갱신에 실패해 24시간 이내의 이전 캐시 값을 대신 반환했다는 뜻이며, 이 경우
 * 웹 계층은 HTTP 206으로 응답한다.
 */
public record PricedQuote(Price price, boolean stale) {
}
