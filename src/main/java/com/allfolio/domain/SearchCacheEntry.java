package com.allfolio.domain;

import java.time.Instant;
import java.util.List;

/**
 * 통합 종목 검색(GET /v1/assets/search) 결과 Redis 캐시 전용 값 타입. 가격 캐시({@code PriceCacheEntry})와
 * 달리 fresh/stale 개념이 없다 — TTL이 지나면 그냥 캐시 미스로 처리한다(Task 026 서브태스크 3).
 */
public record SearchCacheEntry(List<SearchResult> results, Instant cachedAt) {
}
