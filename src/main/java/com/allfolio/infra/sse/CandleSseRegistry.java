package com.allfolio.infra.sse;

import com.allfolio.domain.Candle;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
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
    private final Map<SseEmitter, String> subscriberUserIds = new ConcurrentHashMap<>();

    /** 구독 키 수가 아니라 전체 emitter(구독자) 개수의 총합을 노출한다(ROADMAP 성능 KPI: SSE 동시 커넥션 1,000). */
    public CandleSseRegistry(MeterRegistry meterRegistry) {
        meterRegistry.gauge("allfolio.sse.active.emitters", this,
                registry -> registry.subscribers.values().stream().mapToInt(Set::size).sum());
    }

    /**
     * emitter의 완료·타임아웃·에러 콜백에서 자동으로 {@link #unsubscribe}가 호출되도록 등록한다 —
     * 연결이 어떤 이유로든 끝나면(정상 종료, 타임아웃, 전송 실패로 인한 completeWithError 포함)
     * 레지스트리에서 스스로 정리된다. 호출부(컨트롤러)가 정리를 잊어도 누수되지 않는다.
     *
     * <p>{@code userId}는 이 emitter를 연 요청의 소유자다 — 구독 키({@link CandleSubscriptionKey})
     * 자체는 여러 유저가 공유할 수 있지만, 개별 emitter는 구독 시점에 이미 특정 유저 한 명의 요청으로
     * 열린 것이라 그 정보를 따로 기억해둔다({@link CandlePushScheduler}가 전송 로그에 채우기 위함).
     */
    public void subscribe(CandleSubscriptionKey key, SseEmitter emitter, String userId) {
        subscribers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        subscriberUserIds.put(emitter, userId);
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
        subscriberUserIds.remove(emitter);
    }

    /** {@link CandlePushScheduler}가 emitter별 전송 로그에 userId를 채우기 위해 조회한다. */
    public Optional<String> userIdOf(SseEmitter emitter) {
        return Optional.ofNullable(subscriberUserIds.get(emitter));
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

    /**
     * 컨텍스트 종료 시 열려 있는 모든 SSE 연결을 정상 종료한다. {@code AssetController}가 여는
     * emitter는 타임아웃이 {@code Long.MAX_VALUE}라 서버가 스스로 정리하지 않으면 graceful shutdown이
     * 활성 요청을 기다리다 타임아웃된다(Task 032 code-reviewer 지적, 실측: `Shutdown phase ... still
     * running`/`Graceful shutdown aborted`).
     *
     * <p>{@code @PreDestroy} 대신 {@link ContextClosedEvent}를 듣는 이유: {@code AbstractApplicationContext
     * .doClose()}는 {@code ContextClosedEvent} 발행 → {@code LifecycleProcessor.onClose()}(Tomcat의
     * graceful shutdown 포함, 최대 30초 대기) → {@code destroyBeans()}({@code @PreDestroy} 호출) 순으로
     * 진행된다(바이트코드로 실측 확인). {@code @PreDestroy}로 등록하면 graceful shutdown이 이미 30초를
     * 다 기다린 뒤에야 emitter가 정리돼 타임아웃을 막지 못한다 — 실제로 재현되어 이 방식으로 교체했다.
     * {@code ContextClosedEvent}는 graceful shutdown이 대기를 시작하기 전에 동기 발행되므로 그 전에
     * emitter를 모두 완료시킬 수 있다.
     *
     * <p>{@code complete()} 호출만으로 충분하다 — {@link #subscribe}가 등록해둔 {@code onCompletion}
     * 콜백이 발화해 {@link #unsubscribe}가 자동으로 실행되므로 여기서 {@code subscribers} 등을 직접
     * 비우지 않는다. 콜백 안에서 {@code subscribers}를 수정하므로 원본 맵을 순회하면
     * {@link java.util.ConcurrentModificationException} 위험이 있어 스냅샷을 떠서 순회한다.
     */
    @EventListener(ContextClosedEvent.class)
    public void shutdown() {
        for (Set<SseEmitter> emitters : subscribers.values()) {
            for (SseEmitter emitter : Set.copyOf(emitters)) {
                emitter.complete();
            }
        }
    }
}
