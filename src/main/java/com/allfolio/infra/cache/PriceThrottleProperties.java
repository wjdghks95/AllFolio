package com.allfolio.infra.cache;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 사용자당 시세 조회 요청 제한(Task 022). 캐시가 신선해 외부 API를 호출하지 않는 요청에는 적용하지
 * 않고, 외부 API를 실제로 호출하려는 시도에만 건다 — window 안에서 limit을 초과하면 거부한다.
 */
@Validated
@ConfigurationProperties(prefix = "allfolio.price-throttle")
public record PriceThrottleProperties(
        @Min(1)
        int limit,

        @NotNull
        Duration window
) {
}
