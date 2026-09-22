package com.allfolio.infra.security;

import com.allfolio.domain.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 만료·폐기된 {@code refresh_tokens} 행을 정리하는 배치(ROADMAP Task 019 남은 갭 해소).
 *
 * <p>Refresh Token은 rotation(갱신마다 기존 토큰 즉시 폐기 + 새 토큰 발급) 정책이라, 토큰이
 * "활성 상태로 자연 만료"되기보다 "쓰이자마자 폐기"되는 경우가 훨씬 흔하다 — 활성 사용자는 만료
 * TTL(14일, {@code allfolio.jwt.refresh-token-ttl})이 차기 전에 이미 여러 번 갱신하므로, 정리하지
 * 않으면 폐기된 채로 최대 14일씩 쌓인다. 그래서 삭제 기준(cutoff)은 만료 시각(`expires_at`)뿐 아니라
 * 폐기 시각(`revoked_at`)도 함께 본다({@link RefreshTokenRepository#deleteExpiredOrRevokedBefore}).
 *
 * <p><b>유예 기간을 7일로 정한 근거</b>: 이 값 자체를 짧게 잡아야 할 성능·용량상의 이유는 없다
 * ({@code token_hash} UNIQUE 인덱스 조회라 테이블이 다소 쌓여도 조회 성능에 영향이 없음, Task 019
 * 원 설계 결정 문서 참고). 대신 탈취된 토큰 재사용 탐지 등 사후 감사 목적으로 "방금 폐기된" 토큰이
 * 며칠간 조회 가능하게 남아 있는 편이 유리하다 — 즉시 삭제하면 그 근거가 사라진다. 7일은 "감사
 * 목적에 필요한 최소한의 유예"와 "저장 공간 누적 방지"의 균형점으로 잡은 관례값이며, 특정 KPI에서
 * 역산된 값은 아니다.
 *
 * <p><b>{@code scheduler} 속성을 지정하지 않는 이유와 실제 실행 스케줄러(code-reviewer Minor 지적,
 * 실측 정정)</b>: 하루 1회 실행되는 단순 배치라 별도 전용 {@code TaskScheduler}를 새로 두는 것은 과한
 * 인프라라 판단해 {@code scheduler} 속성을 비워뒀다. 다만 이 프로젝트에는 Spring Boot 4.1의 기본
 * {@code taskScheduler} 자동 구성 빈이 실제로 뜨지 않는다 — {@code TaskSchedulingAutoConfiguration}은
 * {@code @ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})}라서,
 * {@code SseSchedulerConfig}가 {@code sseTaskScheduler} 빈을 등록하는 순간 자동 구성이 백오프한다.
 * 그 결과 컨텍스트에 남는 {@code TaskScheduler} 빈은 {@code sseTaskScheduler} 하나뿐이라
 * {@code ScheduledAnnotationBeanPostProcessor}가 이 유일한 빈을 이 배치에도 그대로 사용한다 — 즉
 * 이 cleanup()은 SSE 전용으로 설계된 {@code sseTaskScheduler}(가상 스레드 기반
 * {@code SimpleAsyncTaskScheduler}, 동시성 상한 없음) 위에서 실행된다. 기능적으로는 문제없지만,
 * {@code SseSchedulerConfig} Javadoc이 의도한 "SSE 전용 격리"가 이 배치에는 적용되지 않는다는 점만
 * 유의할 것.
 */
@Component
public class RefreshTokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupScheduler.class);

    /** 위 클래스 Javadoc 「유예 기간을 7일로 정한 근거」 참고. */
    private static final Duration GRACE_PERIOD = Duration.ofDays(7);

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenCleanupScheduler(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** 매일 새벽 3시(서버 기본 타임존)에 1회 실행 — 다른 배치와 겹치지 않는 한가한 시간대 관례값. */
    @Scheduled(cron = "0 0 3 * * *")
    void cleanup() {
        Instant cutoff = Instant.now().minus(GRACE_PERIOD);
        int deleted = refreshTokenRepository.deleteExpiredOrRevokedBefore(cutoff);
        log.info("만료·폐기된 refresh_tokens {}건 정리 완료 (cutoff={})", deleted, cutoff);
    }
}
