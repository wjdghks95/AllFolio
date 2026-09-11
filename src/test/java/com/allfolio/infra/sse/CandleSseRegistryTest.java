package com.allfolio.infra.sse;

import com.allfolio.domain.Candle;
import com.allfolio.domain.CandleInterval;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 028 「SSE 스트리밍 백엔드(COIN 전용)」 — {@link CandleSseRegistry}의 순수
 * 등록/조회/제거 로직을 검증한다.
 *
 * <p><b>emitter의 완료·에러 콜백이 실제로 실행돼 자동 정리되는지는 여기서 검증하지 않는다</b> —
 * Spring의 {@code ResponseBodyEmitter}는 실제 비동기 서블릿 요청 처리 중에 {@code initialize(Handler)}로
 * 연결된 뒤에야 {@code complete()}/{@code completeWithError()}가 등록된 콜백을 실행한다(바이트코드
 * 직접 확인 — handler가 null인 미연결 emitter는 콜백을 건너뛴다). 그 자동 정리 경로는
 * {@code CandleSseStreamIntegrationTest}(실제 HTTP 서버 기동, 실제 연결 종료)가 end-to-end로
 * 검증한다 — 여기서 bare {@code new SseEmitter()}에 {@code complete()}를 호출해 콜백을 흉내 내면
 * 실제로는 실행되지 않는 경로를 검증하는 거짓 양성 테스트가 된다.
 */
class CandleSseRegistryTest {

    private static final CandleSubscriptionKey KEY =
            new CandleSubscriptionKey("KRW-BTC", "KRW", CandleInterval.DAY);

    private static final Candle CANDLE_A = new Candle(
            new BigDecimal("100000000"), new BigDecimal("101000000"),
            new BigDecimal("99000000"), new BigDecimal("100500000"), Instant.parse("2026-09-10T00:00:00Z"));

    @Test
    void subscribeRegistersEmitterUnderKey() {
        CandleSseRegistry registry = new CandleSseRegistry();
        SseEmitter emitter = new SseEmitter();

        registry.subscribe(KEY, emitter);

        assertThat(registry.activeKeys()).containsExactly(KEY);
        assertThat(registry.subscribersOf(KEY)).containsExactly(emitter);
    }

    @Test
    void unknownKeyReturnsEmptySubscriberSet() {
        CandleSseRegistry registry = new CandleSseRegistry();

        assertThat(registry.subscribersOf(KEY)).isEmpty();
        assertThat(registry.activeKeys()).isEmpty();
    }

    @Test
    void secondSubscriberJoinsSameKey() {
        CandleSseRegistry registry = new CandleSseRegistry();
        SseEmitter first = new SseEmitter();
        SseEmitter second = new SseEmitter();

        registry.subscribe(KEY, first);
        registry.subscribe(KEY, second);

        assertThat(registry.subscribersOf(KEY)).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void explicitUnsubscribeRemovesOnlyThatEmitter() {
        CandleSseRegistry registry = new CandleSseRegistry();
        SseEmitter first = new SseEmitter();
        SseEmitter second = new SseEmitter();
        registry.subscribe(KEY, first);
        registry.subscribe(KEY, second);

        registry.unsubscribe(KEY, first);

        assertThat(registry.subscribersOf(KEY)).containsExactly(second);
        assertThat(registry.activeKeys()).containsExactly(KEY);
    }

    @Test
    void unsubscribingLastEmitterAlsoClearsLastPushedForThatKey() {
        CandleSseRegistry registry = new CandleSseRegistry();
        SseEmitter emitter = new SseEmitter();
        registry.subscribe(KEY, emitter);
        registry.updateLastPushed(KEY, CANDLE_A);

        registry.unsubscribe(KEY, emitter);

        assertThat(registry.subscribersOf(KEY)).isEmpty();
        assertThat(registry.activeKeys()).doesNotContain(KEY);
        // 구독자가 없어진 키는 lastPushed 엔트리도 함께 제거된다 — stale 엔트리가 남지 않는다.
        assertThat(registry.lastPushed(KEY)).isEmpty();
    }

    @Test
    void unsubscribingOneOfManyKeepsLastPushedForRemainingSubscribers() {
        CandleSseRegistry registry = new CandleSseRegistry();
        SseEmitter first = new SseEmitter();
        SseEmitter second = new SseEmitter();
        registry.subscribe(KEY, first);
        registry.subscribe(KEY, second);
        registry.updateLastPushed(KEY, CANDLE_A);

        registry.unsubscribe(KEY, first);

        assertThat(registry.lastPushed(KEY)).contains(CANDLE_A);
    }

    @Test
    void lastPushedUpdateAndReadRoundTrip() {
        CandleSseRegistry registry = new CandleSseRegistry();

        assertThat(registry.lastPushed(KEY)).isEmpty();

        registry.updateLastPushed(KEY, CANDLE_A);

        assertThat(registry.lastPushed(KEY)).contains(CANDLE_A);
    }

    @Test
    void differentIntervalsForSameTickerAreIndependentKeys() {
        CandleSseRegistry registry = new CandleSseRegistry();
        CandleSubscriptionKey dayKey = new CandleSubscriptionKey("KRW-BTC", "KRW", CandleInterval.DAY);
        CandleSubscriptionKey weekKey = new CandleSubscriptionKey("KRW-BTC", "KRW", CandleInterval.WEEK);
        SseEmitter dayEmitter = new SseEmitter();
        SseEmitter weekEmitter = new SseEmitter();

        registry.subscribe(dayKey, dayEmitter);
        registry.subscribe(weekKey, weekEmitter);

        assertThat(registry.subscribersOf(dayKey)).containsExactly(dayEmitter);
        assertThat(registry.subscribersOf(weekKey)).containsExactly(weekEmitter);
        assertThat(registry.activeKeys()).containsExactlyInAnyOrder(dayKey, weekKey);
    }
}
