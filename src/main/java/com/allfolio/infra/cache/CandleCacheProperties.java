package com.allfolio.infra.cache;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * STOCK(국내+해외 공통) 일봉 원본 캐시 설정(Task 028). {@code PriceCacheProperties}(시세 단건 캐시)와
 * 별도 설정 트리로 둔다 — 캔들 캐시는 시계열(범위) 데이터라 신선도 판단 방식이 다르고
 * ({@link CandleCacheStore}는 fresh/stale 2단계 없이 Redis TTL 자체로 만료를 관리한다),
 * 캔들 특유의 "총 조회 범위 상한"·"락 만료" 설정이 추가로 필요하기 때문이다.
 */
@Validated
@ConfigurationProperties(prefix = "allfolio.candle-cache")
public record CandleCacheProperties(
        /** STOCK 일봉 원본 캐시 TTL. 하루 1회 갱신되는 EOD 성격이라 price-cache.stock-fresh-ttl과 동일하게 길게 둔다. */
        @NotNull
        Duration stockDailyTtl,

        /** 캔들 차트가 다루는 최대 조회 범위 상한(연 단위). 무한정 과거로 캐시 확장 요청이 쌓이는 것을 막는다. */
        @Min(1)
        int maxHistoryYears,

        /** cache stampede 방지용 티커 단위 락(CandleRangeLock) 만료 시간. 벤더 API 호출 1건이 끝나기에 충분히 짧게 둔다. */
        @NotNull
        Duration rangeLockTtl
) {
}
