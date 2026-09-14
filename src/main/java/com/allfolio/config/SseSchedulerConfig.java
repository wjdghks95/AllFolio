package com.allfolio.config;

import com.allfolio.infra.logging.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/**
 * SSE 캔들 push 스케줄러(Task 028, {@code infra/sse/CandlePushScheduler})가 쓰는 전용
 * {@link TaskScheduler}.
 *
 * <p><b>구현체 선택 근거(2026-09-11, 이 세션에서 Spring Framework 7.0.8·Spring Boot
 * autoconfigure 4.1.0 jar를 직접 디컴파일해 실측 확인, 추측 아님):</b>
 * <ul>
 *   <li>{@link SimpleAsyncTaskScheduler}는 {@code org.springframework.core.task
 *       .SimpleAsyncTaskExecutor}를 상속하는데, 그 부모가 {@code setVirtualThreads(boolean)}와
 *       {@code setTaskDecorator(TaskDecorator)}를 모두 제공한다 — Virtual Thread와
 *       {@code TaskDecorator}를 동시에 지원하는 표준 {@link TaskScheduler} 구현체다.
 *       {@code ThreadPoolTaskScheduler}는 고정 풀 기반이라 이 프로젝트의 "Virtual Threads 활성
 *       상태에서 풀 기반 설정 추가 금지" 규칙(spring-boot-4.md)과 맞지 않는다.</li>
 *   <li>Spring Boot 4.1의 {@code TaskSchedulingAutoConfiguration}도 {@code spring.threads.virtual
 *       .enabled=true}(이 프로젝트의 기존 설정)일 때 정확히 이 조합 — {@code SimpleAsyncTaskScheduler}
 *       + {@code virtualThreads(true)} + 컨텍스트의 단일 {@code TaskDecorator} 빈을 ObjectProvider로
 *       자동 적용 — 으로 기본 {@code taskScheduler} 빈을 구성함을 바이트코드로 확인했다. 그 암묵적
 *       기본값에 얹혀가는 대신, SSE 전용 빈을 명시적으로 분리해 이름으로 참조한다({@code @Scheduled
 *       (scheduler = SSE_TASK_SCHEDULER)}) — 다른 기능이 나중에 기본 taskScheduler에 다른
 *       {@code TaskDecorator}를 추가하거나 용도를 바꿔도 SSE push 스케줄이 영향받지 않게 하기
 *       위함이다.</li>
 * </ul>
 */
@Configuration
@EnableScheduling
public class SseSchedulerConfig {

    public static final String SSE_TASK_SCHEDULER = "sseTaskScheduler";

    @Bean(SSE_TASK_SCHEDULER)
    public TaskScheduler sseTaskScheduler() {
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setThreadNamePrefix("sse-candle-push-");
        scheduler.setVirtualThreads(true);
        scheduler.setTaskDecorator(new MdcTaskDecorator());
        return scheduler;
    }
}
