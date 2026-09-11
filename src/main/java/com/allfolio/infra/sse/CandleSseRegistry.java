package com.allfolio.infra.sse;

import com.allfolio.domain.Candle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * COIN 캔들 SSE 구독자 레지스트리(Task 028). {@link CandlePushScheduler}가 주기적으로 순회하며
 * {@link #activeKeys()}·{@link #subscribersOf(CandleSubscriptionKey)}로 push 대상을 찾고,
 * {@link #lastPushed(CandleSubscriptionKey)}/{@link #updateLastPushed}로 변경분만 골라 보낸다.
 *
 * <p>구독자별로 "마지막 push 값"을 따로 기억하지 않는다 — 같은 {@link CandleSubscriptionKey}(티커+
 * 통화+interval)를 보는 구독자는 항상 동일한 데이터를 보므로 키 단위로 1개만 기억하면 충분하다.
 */
@Component
public class CandleSseRegistry {

    private final Map<CandleSubscriptionKey, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<CandleSubscriptionKey, Candle> lastPushed = new ConcurrentHashMap<>();

    /**
     * emitter의 완료·타임아웃·에러 콜백에서 자동으로 {@link #unsubscribe}가 호출되도록 등록한다 —
     * 연결이 어떤 이유로든 끝나면(정상 종료, 타임아웃, 전송 실패로 인한 completeWithError 포함)
     * 레지스트리에서 스스로 정리된다. 호출부(컨트롤러)가 정리를 잊어도 누수되지 않는다.
     */
    public void subscribe(CandleSubscriptionKey key, SseEmitter emitter) {
        subscribers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable cleanup = () -> unsubscribe(key, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
    }

    /**
     * 구독자 집합이 비면 그 키의 "마지막 push 값" 엔트리도 함께 제거한다 — stale 엔트리가 무한정
     * 쌓이는 것을 막는다(사용자가 오래전 구독했다 끊은 티커의 캔들이 영영 메모리에 남지 않게).
     */
    public void unsubscribe(CandleSubscriptionKey key, SseEmitter emitter) {
        subscribers.computeIfPresent(key, (k, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
        if (!subscribers.containsKey(key)) {
            lastPushed.remove(key);
        }
    }

    /** {@link CandlePushScheduler}가 매 틱마다 순회할 대상. */
    public Set<CandleSubscriptionKey> activeKeys() {
        return Set.copyOf(subscribers.keySet());
    }

    public Set<SseEmitter> subscribersOf(CandleSubscriptionKey key) {
        return subscribers.getOrDefault(key, Set.of());
    }

    public Optional<Candle> lastPushed(CandleSubscriptionKey key) {
        return Optional.ofNullable(lastPushed.get(key));
    }

    public void updateLastPushed(CandleSubscriptionKey key, Candle candle) {
        lastPushed.put(key, candle);
    }
}
