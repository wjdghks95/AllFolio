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
 * 디바운스 1회 발동은 currency(KRW/USD)별로 요청이 나뉘어 실제로는 2건 팬아웃된다(GET
 * /v1/assets/search가 currency를 필수 파라미터로 요구하는 구조 — SearchCombobox.runSearch,
 * application.yml의 allfolio.search-throttle 주석 참조). limit 값은 이 팬아웃을 반영해 정한 것이다.
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
