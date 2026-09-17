package com.allfolio.infra.sse;

import com.allfolio.domain.Candle;
import com.allfolio.domain.CandleInterval;
import com.allfolio.domain.service.CandleService;
import com.allfolio.infra.cache.PriceCacheProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * code-reviewer M1(Task 031 통합 검증) 회귀 테스트. 병렬화 직후의 {@code pollAndPush()}는 던진
 * 가상 스레드를 join하지 않고 즉시 반환했다 — 그래서 업스트림 호출이 느리면 Spring
 * {@code fixedDelay}의 "이전 실행 완료 후 N초" 보장이 깨지고, 다음 틱이 이전 틱의 처리가 끝나기도
 * 전에 시작될 수 있었다(실측: 업비트 2.5초 지연 스텁 + 구독 키 1개로 20초 관찰 시 동시 in-flight
 * 요청이 항상 3건 유지됨). 이 테스트는 실제 {@code @Scheduled} 타이머를 기다리지 않고
 * {@link CandlePushScheduler#pollAndPush()}를 연속 두 번 직접 호출해, join(timeout) 도입 이후에는
 * 두 번째 호출의 업스트림 조회가 첫 번째 호출의 업스트림 조회와 절대 겹치지 않음을 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class CandlePushSchedulerTickOverlapTest {

    private static final CandleSubscriptionKey KEY =
            new CandleSubscriptionKey("KRW-BTC", "KRW", CandleInterval.DAY);

    @Mock
    private CandleSseRegistry registry;

    @Mock
    private CandleService candleService;

    @Mock
    private SseEmitter emitter;

    @Test
    void secondPollDoesNotOverlapFirstPollsUpstreamCall() {
        when(registry.activeKeys()).thenReturn(Set.of(KEY));
        when(registry.subscribersOf(KEY)).thenReturn(Set.of(emitter));
        lenient().when(registry.userIdOf(emitter)).thenReturn(Optional.empty());
        lenient().when(registry.lastPushed(KEY)).thenReturn(Optional.empty());

        Candle candle = new Candle(new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("1"), Instant.now());
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        when(candleService.fetchLatestCoinCandle(KEY.ticker(), KEY.currency(), KEY.interval()))
                .thenAnswer(invocation -> {
                    int current = inFlight.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, current));
                    try {
                        Thread.sleep(300);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return candle;
                });

        PriceCacheProperties priceCacheProperties = new PriceCacheProperties(
                Duration.ofSeconds(5), Duration.ofHours(12), Duration.ofMinutes(1),
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofSeconds(30), 10);
        CandlePushScheduler scheduler = new CandlePushScheduler(registry, candleService, priceCacheProperties);

        // 실제 스케줄러가 fixedDelay로 순차 호출하는 것을 흉내 낸다 — join이 없다면 두 번째 호출이
        // 시작될 때 첫 번째 호출의 300ms 지연이 아직 안 끝나 inFlight가 2가 됐을 것이다.
        scheduler.pollAndPush();
        scheduler.pollAndPush();

        assertThat(maxConcurrent.get()).isEqualTo(1);
    }
}
