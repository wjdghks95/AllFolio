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
 * <p>Virtual Threads가 활성화된 이 프로젝트에서 하루 1회 실행되는 단순 배치에 별도 전용
 * {@code TaskScheduler}(예: {@code SseSchedulerConfig}처럼 풀 설정을 튜닝한 빈)를 두는 것은 과한
 * 인프라다 — Spring Boot 4.1의 기본 {@code taskScheduler} 빈이 이미 {@code spring.threads.virtual
 * .enabled=true} 설정을 반영해 {@code SimpleAsyncTaskScheduler}(Virtual Thread)로 자동 구성되므로
 * (config/SseSchedulerConfig 클래스 Javadoc의 실측 근거 참고), {@code scheduler} 속성을 지정하지
 * 않고 그 기본값에 위임한다.
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
