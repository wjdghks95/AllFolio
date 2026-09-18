package com.allfolio.infra.sse;

import com.allfolio.domain.Candle;
import com.allfolio.domain.CandleInterval;
import com.allfolio.domain.service.CandleService;
import com.allfolio.infra.cache.PriceCacheProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * {@link CandlePushScheduler#pushIfChanged}의 permit 타임아웃 skip 분기(150~161행) 회귀 테스트.
 * {@code upbitPollConcurrency=1}로 두고 서로 다른 구독 키 2개를 동시에 폴링 트리거하면, 한쪽만
 * permit을 획득해 {@link CandleService#fetchLatestCoinCandle}을 호출하고 나머지는 permit 대기
 * 타임아웃({@code coinFreshTtl})에 걸려 그 키만 조용히 건너뛴다(스케줄러 전체는 멈추지 않는다).
 *
 * <p>{@link CandlePushSchedulerTickOverlapTest}는 같은 키에 대한 세대 간(join) 겹침 방지를
 * 검증할 뿐, 세마포어 소진으로 인한 키 단위 skip은 다루지 않아 이 테스트로 보강한다.
 */
@ExtendWith(MockitoExtension.class)
class CandlePushSchedulerSemaphoreSkipTest {

    private static final CandleSubscriptionKey KEY_A =
            new CandleSubscriptionKey("KRW-BTC", "KRW", CandleInterval.DAY);
    private static final CandleSubscriptionKey KEY_B =
            new CandleSubscriptionKey("KRW-ETH", "KRW", CandleInterval.DAY);

    @Mock
    private CandleSseRegistry registry;

    @Mock
    private CandleService candleService;

    @Mock
    private SseEmitter emitter;

    @Test
    void onlyOneKeyAcquiresPermitWhenConcurrencyIsOne() throws InterruptedException {
        when(registry.activeKeys()).thenReturn(Set.of(KEY_A, KEY_B));
        when(registry.subscribersOf(KEY_A)).thenReturn(Set.of(emitter));
        when(registry.subscribersOf(KEY_B)).thenReturn(Set.of(emitter));
        lenient().when(registry.userIdOf(emitter)).thenReturn(Optional.empty());
        lenient().when(registry.lastPushed(KEY_A)).thenReturn(Optional.empty());
        lenient().when(registry.lastPushed(KEY_B)).thenReturn(Optional.empty());

        Candle candle = new Candle(new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("1"), Instant.now());
        AtomicInteger callCount = new AtomicInteger(0);

        // permit을 딴 쪽이 permit 대기 타임아웃(200ms)보다 훨씬 길게(400ms) 붙잡고 있어야, 진 쪽이
        // tryAcquire 타임아웃에 확실히 걸려 fetchLatestCoinCandle을 호출하지 않는 skip 분기를 탄다.
        lenient().when(candleService.fetchLatestCoinCandle(KEY_A.ticker(), KEY_A.currency(), KEY_A.interval()))
                .thenAnswer(invocation -> {
                    callCount.incrementAndGet();
                    Thread.sleep(400);
                    return candle;
                });
        lenient().when(candleService.fetchLatestCoinCandle(KEY_B.ticker(), KEY_B.currency(), KEY_B.interval()))
                .thenAnswer(invocation -> {
                    callCount.incrementAndGet();
                    Thread.sleep(400);
                    return candle;
                });

        PriceCacheProperties priceCacheProperties = new PriceCacheProperties(
                Duration.ofMillis(200), Duration.ofHours(12), Duration.ofMinutes(1),
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofSeconds(30), 1);
        CandlePushScheduler scheduler = new CandlePushScheduler(registry, candleService, priceCacheProperties,
                new SimpleMeterRegistry());

        scheduler.pollAndPush();

        // pollAndPush()는 join 타임아웃(coinFreshTtl=200ms)에 걸리는 즉시 반환하므로, 진 쪽 스레드가
        // 아직 permit 대기 중인 상태에서 곧바로 단언하면 "즉시 skip"과 "타임아웃 없이 결국 permit을
        // 얻어 늦게라도 호출됨"(acquire() 뮤테이션)을 구분하지 못한다. 이긴 쪽(400ms)이 permit을 반납한
        // 뒤 진 쪽이 뮤테이션 상태에서 실제로 호출을 시도할 시간을 충분히 준 뒤에 단언해야 한다.
        Thread.sleep(700);

        // 두 키가 동시에 permit(concurrency=1)을 다투므로 한쪽만 획득해 업스트림을 호출하고,
        // 나머지는 tryAcquire 타임아웃(coinFreshTtl=200ms)에 걸려 조용히 skip돼야 한다.
        assertThat(callCount.get()).isEqualTo(1);
    }
}
