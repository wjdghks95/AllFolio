package com.allfolio.infra.sse;

import com.allfolio.config.SseSchedulerConfig;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Candle;
import com.allfolio.domain.PrecisionScale;
import com.allfolio.domain.service.CandleService;
import com.allfolio.infra.logging.MdcKeys;
import com.allfolio.infra.logging.MdcPropagation;
import com.allfolio.web.dto.CandleBarResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * COIN 캔들 SSE push 스케줄러(Task 028). {@link CandleSseRegistry}가 들고 있는 구독 키를 주기적으로
 * 순회해 최신 캔들을 조회하고, 직전에 보낸 값과 달라졌을 때만 그 키의 구독자 전원에게 전송한다.
 *
 * <p><b>Last-Event-ID 기반 30초 재전송 버퍼는 구현하지 않는다</b>(구 PRD §8.3의 다른 SSE 기능이
 * 전제한 설계와 의도적으로 다른 점). 이 스케줄러는 폴링 기반이라 재연결 시 "현재 상태"를 다시
 * 보내는 것만으로 충분하다 — 재연결한 클라이언트는 곧바로 다음 폴링 틱에서 최신 캔들을 받는다.
 * 과거 이벤트를 정확히 이어붙일 필요가 있는 요구사항이 아니므로 별도 버퍼 인프라(Redis 등)를
 * 두지 않는다.
 */
@Component
public class CandlePushScheduler {

    private static final Logger log = LoggerFactory.getLogger(CandlePushScheduler.class);

    private final CandleSseRegistry registry;
    private final CandleService candleService;

    public CandlePushScheduler(CandleSseRegistry registry, CandleService candleService) {
        this.registry = registry;
        this.candleService = candleService;
    }

    /**
     * 폴링 주기는 {@code allfolio.price-cache.coin-fresh-ttl}(COIN 캐시 신선도 기준, 기본 10초)과
     * 맞춘다 — COIN 캔들 경로는 캐시 없는 패스스루라({@link CandleService} 클래스 Javadoc 참고) 이보다
     * 짧게 폴링해도 매번 업비트를 실제로 호출할 뿐 더 자주 새 데이터를 받는 게 아니다.
     * {@code fixedDelayString}에 이 프로퍼티(예: {@code "10s"})를 그대로 넘기면 Spring이
     * {@code DurationFormatterUtils}로 파싱해 밀리초로 환산한다(Long 상수 리터럴이 아니어도 동작함을
     * 이 세션에서 바이트코드로 확인).
     *
     * <p>이 틱 전체에 새 traceId를 발급해 MDC에 채운다 — 개별 구독자 전송이 서로 다른 Virtual
     * Thread에서 실행돼도 "같은 폴링 사이클에서 발생한 전송"임을 로그로 상관지을 수 있게 한다.
     * userId는 채우지 않는다 — 하나의 구독 키를 서로 다른 유저가 동시에 구독할 수 있어(공유 브로드캐스트)
     * 틱 단위로 유일하게 정할 수 없다.
     */
    @Scheduled(fixedDelayString = "${allfolio.price-cache.coin-fresh-ttl}",
            scheduler = SseSchedulerConfig.SSE_TASK_SCHEDULER)
    void pollAndPush() {
        MDC.put(MdcKeys.TRACE_ID, UUID.randomUUID().toString());
        try {
            for (CandleSubscriptionKey key : registry.activeKeys()) {
                pushIfChanged(key);
            }
        } finally {
            MDC.clear();
        }
    }

    private void pushIfChanged(CandleSubscriptionKey key) {
        Set<SseEmitter> subscribers = registry.subscribersOf(key);
        if (subscribers.isEmpty()) {
            return;
        }
        Candle latest;
        try {
            latest = candleService.fetchLatestCoinCandle(key.ticker(), key.currency(), key.interval());
        } catch (RuntimeException e) {
            log.warn("SSE 캔들 폴링 실패: key={}", key, e);
            return;
        }
        if (latest.equals(registry.lastPushed(key).orElse(null))) {
            return;
        }
        registry.updateLastPushed(key, latest);
        CandleBarResponse payload = toPayload(key, latest);
        for (SseEmitter emitter : subscribers) {
            sendAsync(key, emitter, payload);
        }
    }

    /**
     * heartbeat는 fixedDelay=30000(고정값)으로 둔다 — 프록시 타임아웃 방지가 목적인 관례값이라
     * 이 값 자체를 튜닝할 이유가 아직 없다.
     */
    @Scheduled(fixedDelay = 30_000, scheduler = SseSchedulerConfig.SSE_TASK_SCHEDULER)
    void sendHeartbeat() {
        MDC.put(MdcKeys.TRACE_ID, UUID.randomUUID().toString());
        try {
            for (CandleSubscriptionKey key : registry.activeKeys()) {
                for (SseEmitter emitter : registry.subscribersOf(key)) {
                    sendHeartbeatAsync(emitter);
                }
            }
        } finally {
            MDC.clear();
        }
    }

    /**
     * 개별 emitter로의 send()는 각각 별도 Virtual Thread에서 {@link MdcPropagation#wrap}으로 감싸
     * 실행한다 — 스케줄러 스레드에서 순차 전송하면 느리거나 끊긴 클라이언트 하나가 나머지 전원의
     * push를 지연시킨다.
     *
     * <p>전송 실패(IOException)는 {@code emitter.completeWithError()}로 넘긴다 — 직접
     * {@code registry.unsubscribe()}를 호출하지 않는 이유는, {@link CandleSseRegistry#subscribe}가
     * 이미 onError 콜백에서 정리 로직을 등록해뒀기 때문이다(단일 소스). {@code send()}가 IOException을
     * 던져도 Spring이 자동으로 completeWithError를 호출해주지 않으므로(호출부 책임) 여기서 명시적으로
     * 넘겨야 그 콜백이 실행된다.
     */
    private void sendAsync(CandleSubscriptionKey key, SseEmitter emitter, CandleBarResponse payload) {
        Thread.ofVirtual().start(MdcPropagation.wrap(() -> {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(Instant.now().toEpochMilli()))
                        .name("candle")
                        .data(payload));
                log.debug("SSE 캔들 이벤트 전송 완료: key={}", key);
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (IllegalStateException ignored) {
                // 이미 완료된 emitter — onCompletion/onError 콜백에서 이미 정리됐다.
            }
        }));
    }

    private void sendHeartbeatAsync(SseEmitter emitter) {
        Thread.ofVirtual().start(MdcPropagation.wrap(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (IllegalStateException ignored) {
                // 이미 완료된 emitter.
            }
        }));
    }

    private CandleBarResponse toPayload(CandleSubscriptionKey key, Candle candle) {
        int scale = PrecisionScale.scaleFor(AssetType.COIN, key.currency());
        return new CandleBarResponse(candle.candleAt().toString(),
                scaled(candle.open(), scale), scaled(candle.high(), scale),
                scaled(candle.low(), scale), scaled(candle.close(), scale));
    }

    private String scaled(BigDecimal amount, int scale) {
        return amount.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }
}
