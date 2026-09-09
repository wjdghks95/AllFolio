package com.allfolio.infra.cache;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 통합 종목 검색(GET /v1/assets/search) 결과 캐시 TTL(Task 026 서브태스크 3). 시세와 달리 종목명·
 * 마켓 목록은 실시간성이 필요 없어 시세 캐시(초~시간 단위)보다 훨씬 넉넉하게 잡는다.
 */
@Validated
@ConfigurationProperties(prefix = "allfolio.search-cache")
public record SearchCacheProperties(
        @NotNull
        Duration ttl
) {
}
