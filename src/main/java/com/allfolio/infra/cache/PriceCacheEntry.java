package com.allfolio.infra.cache;

import java.math.BigDecimal;
import java.time.Instant;

/** Redis에 저장하는 캐시 전용 값 타입 — cachedAt으로 fresh/stale 여부를 판정한다(Task 022). */
public record PriceCacheEntry(BigDecimal amount, String currency, Instant asOf, Instant cachedAt) {
}
