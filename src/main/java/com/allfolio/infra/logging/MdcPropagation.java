package com.allfolio.infra.logging;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * MDC(traceId/userId)는 {@link ThreadLocal} 기반이라 스레드 경계를 넘지 않는다 — 원 요청 스레드가
 * 채운 값은 다른 스레드에서 실행되는 작업에 자동으로 전파되지 않는다.
 *
 * <p><b>Task 028(SSE) 착수 시 반드시 적용할 것</b>: SSE 이벤트를 실제로 {@code emitter.send()}하는
 * 백그라운드 스레드/executor(구현 방식이 별도 {@code Executor} 빈이든, 매 이벤트마다
 * {@code Thread.ofVirtual()}로 직접 띄우는 방식이든)에 이 유틸로 감싼 작업을 실행해야
 * traceId/userId가 로그에 남는다. 감싸지 않으면 SSE 전송 로그에서 어느 요청/유저에서 발생한
 * 이벤트인지 추적할 수 없다.
 *
 * <p>Spring이 이런 용도로 {@code org.springframework.core.task.TaskDecorator}를 표준 제공하지만,
 * 이 프로젝트는 Virtual Threads(spring.threads.virtual.enabled=true) 환경이라 아직 {@code @Async}용
 * {@code TaskExecutor} 빈이 없다. 그래서 특정 Executor 구현에 종속되지 않도록 정적 메서드로 제공한다
 * — {@code Thread.ofVirtual().start(MdcPropagation.wrap(task))}처럼 실행 방식과 무관하게 쓸 수 있다.
 */
public final class MdcPropagation {

    private MdcPropagation() {
    }

    /**
     * 호출 시점(래핑 시점)의 MDC 컨텍스트 맵을 캡처해, 반환된 {@link Runnable}이 어느 스레드에서
     * 실행되든 그 스레드에 주입한다. 실행이 끝나면 그 스레드의 MDC를 래핑 이전 상태로 되돌린다
     * (스레드가 이후 재사용돼도 값이 새어 나가지 않도록).
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            applyContext(context);
            try {
                task.run();
            } finally {
                applyContext(previous);
            }
        };
    }

    /** {@link #wrap(Runnable)}와 동일하되 반환값이 있는 작업(예: SSE 전송 결과를 돌려주는 작업)용. */
    public static <V> Callable<V> wrap(Callable<V> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            applyContext(context);
            try {
                return task.call();
            } finally {
                applyContext(previous);
            }
        };
    }

    private static void applyContext(Map<String, String> context) {
        if (context != null) {
            MDC.setContextMap(context);
        } else {
            MDC.clear();
        }
    }
}
