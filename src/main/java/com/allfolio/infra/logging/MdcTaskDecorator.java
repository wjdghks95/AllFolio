package com.allfolio.infra.logging;

import org.springframework.core.task.TaskDecorator;

/**
 * {@link MdcPropagation#wrap(Runnable)}을 Spring {@link TaskDecorator} 형태로 노출한다.
 *
 * <p>Task 028(SSE)이 SSE 이벤트 전송용 {@code TaskExecutor}/{@code ThreadPoolTaskExecutor} 빈을
 * 새로 둔다면(Virtual Thread executor라도 {@code TaskDecorator}는 그대로 적용된다),
 * {@code setTaskDecorator(new MdcTaskDecorator())}로 등록해 traceId/userId를 실행 스레드에
 * 전파할 수 있다. Executor 빈 없이 직접 {@code Thread.ofVirtual()}로 띄우는 방식을 택한다면
 * {@link MdcPropagation#wrap(Runnable)}을 바로 쓰면 된다 — 둘은 동일한 전파 로직을 공유한다.
 */
public final class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return MdcPropagation.wrap(runnable);
    }
}
