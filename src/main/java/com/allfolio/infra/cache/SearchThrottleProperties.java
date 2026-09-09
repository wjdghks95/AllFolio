package com.allfolio.infra.cache;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 사용자당 종목 검색 요청 제한(Task 026 서브태스크 3). 프론트엔드 자동완성 디바운스(300~400ms)보다
 * 넉넉해야 정상적인 타이핑 흐름이 429로 막히지 않는다 — 시세 조회용 PriceThrottle(초당 1건)보다
 * 훨씬 관대하게 잡는다.
 */
@Validated
@ConfigurationProperties(prefix = "allfolio.search-throttle")
public record SearchThrottleProperties(
        @Min(1)
        int limit,

        @NotNull
        Duration window
) {
}
