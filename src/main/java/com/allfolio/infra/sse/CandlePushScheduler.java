package com.allfolio.infra.sse;

import com.allfolio.config.SseSchedulerConfig;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Candle;
import com.allfolio.domain.PrecisionScale;
import com.allfolio.domain.service.CandleService;
import com.allfolio.infra.cache.PriceCacheProperties;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

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
    private final Duration pollTickJoinTimeout;

    /**
     * 구독 키 fan-out 병렬 폴링(위 클래스 Javadoc의 "구독 키 단위 병렬화" 참고)이 업비트로 보내는
     * 동시 요청 수의 상한(Task 031 k6 부하테스트, 구독 키 500개에서 무제한 fan-out으로 업비트 429·
     * Resilience4j {@code upbit} CB half_open 전환 재발 실측). 틱 경계 보존을 위한 join 리스트에는
     * permit 대기로 블로킹된 스레드도 그대로 포함된다 — 이 세마포어는 in-flight 상한만 걸 뿐 폴링 구조
     * 자체는 바꾸지 않는다.
     */
    private final Semaphore upbitPollSemaphore;

    public CandlePushScheduler(CandleSseRegistry registry, CandleService candleService,
            PriceCacheProperties priceCacheProperties) {
        this.registry = registry;
        this.candleService = candleService;
        this.pollTickJoinTimeout = priceCacheProperties.coinFreshTtl();
        this.upbitPollSemaphore = new Semaphore(priceCacheProperties.upbitPollConcurrency());
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
     * userId는 틱 단위로는 채우지 않는다 — 하나의 구독 키를 서로 다른 유저가 동시에 구독할 수 있어
     * (공유 브로드캐스트) 틱 단위로 유일하게 정할 수 없다. 대신 emitter별 전송 직전에
     * {@link #sendAsync}가 {@link CandleSseRegistry#userIdOf}로 그 emitter의 소유자를 조회해
     * 개별 스냅샷에만 채운다.
     *
     * <p><b>구독 키 단위 병렬화(Task 031 실측 반영)</b>: k6로 실측한 결과 순차 for-loop는 구독 키
     * 하나마다 업비트에 블로킹 HTTP 호출을 보내는 구조라 N이 늘수록 한 틱 소요 시간이 그대로
     * 늘어났다(N=100→평균 34초, N=500→평균 62초, 목표 10초 대비 각각 3.4배/6.2배, `loadtest/results.md`
     * 참고). 그래서 키별 처리(`pushIfChanged`)를 {@code Thread.ofVirtual()}로 병렬 실행한다 — 이미
     * emitter 단위 전송({@link #sendAsync})이 같은 패턴을 쓰고 있어 일관된 방식이다. traceId는
     * 루프 안(스케줄러 스레드 컨텍스트)에서 {@code MdcPropagation.wrap()}을 호출해 캡처하므로 틱
     * 단위 traceId가 그대로 전파된다.
     *
     * <p><b>틱 경계 보존(fixedDelay 세대 중첩 방지, code-reviewer M1 실측 수정)</b>: 병렬화한
     * 스레드를 그냥 던지고 반환하면(fire-and-forget) Spring의 {@code fixedDelay}가 "이전 실행이
     * 끝난 뒤 N초 후 다음 실행"을 보장한다는 전제가 깨진다 — 이 메서드의 "실행 완료"가 실제 작업
     * (업비트 호출 포함) 완료보다 훨씬 먼저 반환돼버려서, 업스트림이 느려지면 다음 틱이 이전 틱의
     * 처리가 끝나기도 전에 시작될 수 있었다(실측: 업비트 응답 2.5초 지연 스텁 + 구독 키 1개로 20초
     * 관찰 시 같은 키에 대한 동시 in-flight 요청이 항상 3건 유지됨 — 3세대의 폴링 스레드가 같은
     * 키를 동시에 처리하고 있었다). 그래서 이 루프에서 던진 스레드를 모아 두었다가
     * {@link Thread#join(Duration)}으로 전부 기다린 뒤에야 반환한다.
     *
     * <p>join 타임아웃은 폴링 주기 자체({@code coinFreshTtl}, 기본 10초)로 둔다 — 그보다 짧게 잡으면
     * 정상적인 처리(수십~수백ms)도 여유 없이 타임아웃에 걸릴 위험이 있고, 그보다 훨씬 길게 잡으면
     * 병리적으로 느린 키 하나가 다음 틱 전체를 무기한 지연시킬 수 있다. 폴링 주기와 동일하게 두면
     * 정상 케이스에서는 "이번 틱 처리가 다음 틱 시작 전에 끝난다"가 실질적으로 항상 성립하고,
     * 병리적으로 느린 응답이 있어도 세대 중첩이 최대 2세대로 제한된다(join이 타임아웃돼도 그
     * 가상 스레드를 강제로 취소하지는 않는다 — Virtual Thread에 적절한 취소 API가 없고, 늦게 와도
     * {@code pushIfChanged}의 dedup(valueEquals)로 걸러지므로 안전하다). 세대가 무한정 쌓이는
     * 것(3세대 이상)은 이 타임아웃으로 방지되지만, 세대가 전혀 안 겹치는 것까지는 보장하지 않는다.
     *
     * <p>트레이드오프: 이 join 때문에 {@code pollAndPush()} 자체의 실행 시간이 팬아웃한 구독 키
     * 처리 중 가장 느린 것까지 포함하게 된다 — {@code fixedDelay} 계약(다음 실행은 이번 실행이
     * 실질적으로 끝난 뒤에 시작)을 지키려면 이게 목적 자체이므로 의도된 트레이드오프다. 키 간
     * 순서 보장은 여전히 하지 않는다(원래도 요구사항이 아니었다) — {@link CandleSseRegistry#lastPushed}/
     * {@link CandleSseRegistry#updateLastPushed}가 {@code ConcurrentHashMap} 기반이라 여러 키를
     * 병렬로 갱신해도 안전함은 유지된다.
     */
    @Scheduled(fixedDelayString = "${allfolio.price-cache.coin-fresh-ttl}",
            scheduler = SseSchedulerConfig.SSE_TASK_SCHEDULER)
    void pollAndPush() {
        MDC.put(MdcKeys.TRACE_ID, UUID.randomUUID().toString());
        try {
            List<Thread> tickThreads = new ArrayList<>();
            for (CandleSubscriptionKey key : registry.activeKeys()) {
                tickThreads.add(Thread.ofVirtual().start(MdcPropagation.wrap(() -> pushIfChanged(key))));
            }
            for (Thread thread : tickThreads) {
                try {
                    thread.join(pollTickJoinTimeout);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
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
            upbitPollSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            latest = candleService.fetchLatestCoinCandle(key.ticker(), key.currency(), key.interval());
        } catch (RuntimeException e) {
            log.warn("SSE 캔들 폴링 실패: key={}", key, e);
            return;
        } finally {
            upbitPollSemaphore.release();
        }
        if (latest.valueEquals(registry.lastPushed(key).orElse(null))) {
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
     *
     * <p><b>이 메서드는 프록시 타임아웃 방지뿐 아니라 죽은 연결을 정리하는 유일한 방어적 스윕이기도
     * 하다</b>(SSE 인프라 갭 조사, 2026-09-17). {@link SseEmitter}/{@code ResponseBodyEmitter}에는
     * "완료 여부"를 외부에서 조회하는 공개 API가 없어(바이트코드 확인 — {@code complete} 필드는
     * private, getter 없음) 죽은 연결을 감지하는 유일한 방법은 실제로 {@code send()}를 시도해 실패를
     * 관찰하는 것뿐이다. 그래서 {@link CandleSseRegistry}의 모든 구독자를 30초마다 순회하며 실제로
     * 전송을 시도하는 이 메서드가 곧 "구독 키 정리 스윕"이다 — 별도의 정리 전용 {@code @Scheduled}를
     * 새로 추가하면 정확히 같은 일(전 구독자 순회 + send() 시도 + 실패 시 completeWithError)을
     * 중복 구현하게 된다.
     *
     * <p>실측(2026-09-17, curl {@code --max-time}으로 클라이언트가 FIN을 보내는 정상 종료 재현):
     * 구독 후 클라이언트가 연결을 끊으면 다음 heartbeat 틱에서 {@code send()}가 IOException으로
     * 실패해 {@link CandleSseRegistry#unsubscribe}가 호출됐다 — 정리까지 걸린 시간은 heartbeat 주기
     * (최대 30초)에 바운드된다. 다만 TCP FIN 없이 연결이 끊기는 경우(전원 차단·네트워크 단절)는
     * curl로 재현할 수 없었고, 이 경우 OS 소켓 버퍼링·TCP 재전송 타임아웃 때문에 {@code send()}
     * 자체가 실패로 판정되기까지 heartbeat 주기보다 훨씬 오래 걸릴 수 있다 — 이는 애플리케이션
     * 코드가 아닌 OS/네트워크 계층의 타이밍이라 스케줄 주기를 조정해도 근본적으로 해결되지 않는다.
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
        Runnable task = wrapWithEmitterUserId(emitter, () -> {
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
        });
        Thread.ofVirtual().start(task);
    }

    private void sendHeartbeatAsync(SseEmitter emitter) {
        Runnable task = wrapWithEmitterUserId(emitter, () -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (IllegalStateException ignored) {
                // 이미 완료된 emitter.
            }
        });
        Thread.ofVirtual().start(task);
    }

    /**
     * {@link MdcPropagation#wrap}은 호출 시점(이 메서드 호출 시점)의 MDC 스냅샷을 캡처할 뿐 실행
     * 시점에 다시 읽지 않는다 — 그래서 emitter별로 다른 userId를 각 Virtual Thread에 전파하려면,
     * {@code wrap()}을 호출하는 "그 순간"에만 스케줄러 스레드의 MDC에 이 emitter의 userId를 잠깐
     * 넣었다가 스냅샷을 캡처한 직후 되돌려야 한다. 스케줄러 스레드 자체의 MDC(틱 단위 traceId)는
     * 오염시키지 않는다 — 다음 emitter로 넘어갈 때 이전 emitter의 userId가 새어 남는 것을 방지한다.
     */
    private Runnable wrapWithEmitterUserId(SseEmitter emitter, Runnable task) {
        Optional<String> userId = registry.userIdOf(emitter);
        userId.ifPresent(id -> MDC.put(MdcKeys.USER_ID, id));
        try {
            return MdcPropagation.wrap(task);
        } finally {
            userId.ifPresent(id -> MDC.remove(MdcKeys.USER_ID));
        }
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
