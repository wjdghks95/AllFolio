package com.allfolio.infra.cache;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 자산 유형별 시세 캐시 신선도 기준(Task 022). COIN(업비트 실시간 시세)은 짧게, STOCK(공공데이터포털
 * EOD)·CASH-USD(환율, 일 단위 갱신)는 길게 설정한다. staleCeiling은 신선도와 무관하게 캐시 자체를
 * stale 폴백으로라도 쓸 수 있는 최종 한계이며, 이를 넘기면 캐시가 사라져 503으로 전환된다.
 *
 * <p>{@code upbitPollConcurrency}는 캐시 신선도가 아니라 {@code CandlePushScheduler}의 업비트 폴링
 * 동시성 상한이다(Task 031 후속). 이름이 "price-cache" prefix를 쓰지만 이 record가 이미
 * {@link com.allfolio.config.CacheConfig}에 등록돼 있어 재사용했다.
 */
@Validated
@ConfigurationProperties(prefix = "allfolio.price-cache")
public record PriceCacheProperties(
        @NotNull
        Duration coinFreshTtl,

        @NotNull
        Duration stockFreshTtl,

        /**
         * 미국 주식(STOCK+USD, Twelve Data) 전용 신선도 기준(Task 025). 공공데이터포털(EOD, 하루 단위
         * 갱신)과 달리 Twelve Data는 장중 실시간에 가까운 값을 주지만, 무료 플랜 호출 한도(분당 8회)와의
         * 절충으로 1분을 기본값으로 둔다.
         */
        @NotNull
        Duration stockUsFreshTtl,

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
        Duration negativeTtl,

        /**
         * {@code CandlePushScheduler.pollAndPush()}가 구독 키를 fan-out 병렬 폴링할 때 동시에 업비트를
         * 호출할 수 있는 최대 개수(Task 031 k6 부하테스트에서 무제한 fan-out으로 업비트 429·CB
         * half_open 재발 실측). 20~30 사이 보수적인 값으로 시작하고 k6 재측정으로 조정한다.
         *
         * <p>{@code @Positive}로 0/음수 설정을 부팅 시점에 거부한다(code-reviewer Major 3 실측) — 0으로
         * 기동하면 서버는 정상 뜨지만(헬스체크 UP) {@code CandlePushScheduler}의 permit 획득이 전부
         * 막혀 SSE 캔들 이벤트가 로그·메트릭 어디에도 안 남고 조용히 0건이 되는 무음 장애가 재현됐다.
         * {@code @DefaultValue("25")}는 yml에 이 키 자체가 통째로 빠졌을 때(바인딩 대상이 아예 없으면
         * int 기본값 0으로 바인딩돼 같은 무음 장애가 재현됨, 실측) 25로 폴백하기 위함이다 — application.yml의
         * "기본 25" 주석이 코드가 아닌 yml 값 하나에만 의존하지 않도록 한다.
         */
        @Positive
        @DefaultValue("25")
        int upbitPollConcurrency
) {
}
