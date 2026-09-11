package com.allfolio.infra.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 014 「남은 갭」(Task 028 SSE 착수 전 선행 정리) — MdcPropagation이 MDC(스레드
 * 로컬)를 다른 스레드에서 실행되는 작업에 전파하고, 실행이 끝난 뒤 대상 스레드의 MDC를 실행 전
 * 상태로 되돌리는지(재사용돼도 오염되지 않는지) 검증한다.
 */
class MdcPropagationTest {

    @Test
    void propagatesMdcAcrossThreadsAndRestoresTargetThreadAfterward() throws InterruptedException {
        MDC.put(MdcKeys.TRACE_ID, "trace-from-thread-a");
        MDC.put(MdcKeys.USER_ID, "user-from-thread-a");

        AtomicReference<String> observedTraceId = new AtomicReference<>();
        AtomicReference<String> observedUserId = new AtomicReference<>();
        AtomicReference<String> traceIdAfterRunOnThreadB = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Runnable wrapped = MdcPropagation.wrap(() -> {
            observedTraceId.set(MDC.get(MdcKeys.TRACE_ID));
            observedUserId.set(MDC.get(MdcKeys.USER_ID));
        });

        Thread threadB = Thread.ofVirtual().unstarted(() -> {
            // 스레드 B가 이전에 다른 작업에 쓰였다고 가정하고 이질적인 값을 미리 심어둔다.
            MDC.put(MdcKeys.TRACE_ID, "stale-value-from-previous-task-on-thread-b");
            wrapped.run();
            traceIdAfterRunOnThreadB.set(MDC.get(MdcKeys.TRACE_ID));
            done.countDown();
        });
        threadB.start();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(observedTraceId.get()).isEqualTo("trace-from-thread-a");
        assertThat(observedUserId.get()).isEqualTo("user-from-thread-a");
        // 실행 후에는 래핑 이전 스레드 B 자신의 값으로 복원돼야 한다 — 전파된 값이 새어 남으면 안 된다.
        assertThat(traceIdAfterRunOnThreadB.get()).isEqualTo("stale-value-from-previous-task-on-thread-b");

        MDC.clear();
    }

    @Test
    void wrappingWithoutSourceContextClearsTargetThreadDuringExecution() throws InterruptedException {
        MDC.clear(); // 캡처 시점(스레드 A)에 MDC가 비어 있는 상태

        AtomicReference<String> observedDuringRun = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Runnable wrapped = MdcPropagation.wrap(() -> observedDuringRun.set(MDC.get(MdcKeys.TRACE_ID)));

        Thread threadB = Thread.ofVirtual().unstarted(() -> {
            MDC.put(MdcKeys.TRACE_ID, "stale-value");
            wrapped.run();
            done.countDown();
        });
        threadB.start();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(observedDuringRun.get()).isNull();
    }
}
