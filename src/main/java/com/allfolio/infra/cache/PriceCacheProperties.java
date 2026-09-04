package com.allfolio.infra.cache;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 자산 유형별 시세 캐시 신선도 기준(Task 022). COIN(업비트 실시간 시세)은 짧게, STOCK(공공데이터포털
 * EOD)·CASH-USD(환율, 일 단위 갱신)는 길게 설정한다. staleCeiling은 신선도와 무관하게 캐시 자체를
 * stale 폴백으로라도 쓸 수 있는 최종 한계이며, 이를 넘기면 캐시가 사라져 503으로 전환된다.
 */
@Validated
@ConfigurationProperties(prefix = "allfolio.price-cache")
public record PriceCacheProperties(
        @NotNull
        Duration coinFreshTtl,

        @NotNull
        Duration stockFreshTtl,

        @NotNull
        Duration cashUsdFreshTtl,

        @NotNull
        Duration staleCeiling
) {
}
