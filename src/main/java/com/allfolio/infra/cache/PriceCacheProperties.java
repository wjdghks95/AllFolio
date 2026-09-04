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
        Duration staleCeiling,

        /**
         * 시세 조회 실패(존재하지 않는 티커 등) 결과를 부정 캐싱하는 TTL(Task 023 Major 2). 실패한 자산이
         * 있으면 GET /v1/portfolio를 반복 호출할 때마다 외부 API가 상한 없이 불리는 문제를 막는다.
         * 10초~1분 범위에서 30초를 기본값으로 둔다 — 너무 짧으면(예: 5초) 남용 방지 효과가 거의 없고,
         * 너무 길면(예: 5분) 티커가 나중에 정상 등록돼도 한동안 계속 실패로 잘못 응답한다.
         */
        @NotNull
        Duration negativeTtl
) {
}
