package com.allfolio.infra.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link CandleThrottle}의 Redis 장애 fail-open 분기(code-reviewer 7번 지적, Task 031 통합 검증) —
 * 지금까지 코드만 있고 테스트가 없던 {@code DataAccessException} 분기를 검증한다. 실제 Redis(Testcontainers)
 * 대신 {@link StringRedisTemplate}을 mock으로 대체해 {@code execute()}가 던지는 장애를 직접 흉내낸다
 * (PriceThrottleTest는 실제 Testcontainers Redis로 정상 경로만 검증하고, 이런 장애 주입은 불가능하다).
 */
@ExtendWith(MockitoExtension.class)
class CandleThrottleTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void tryAcquireFailsOpenWhenRedisThrowsDataAccessException() {
        when(stringRedisTemplate.execute(any(), any(List.class), any()))
                .thenThrow(new QueryTimeoutException("redis unavailable"));
        CandleThrottle throttle = new CandleThrottle(stringRedisTemplate,
                new CandleThrottleProperties(3, Duration.ofSeconds(1)));

        assertThat(throttle.tryAcquire(UUID.randomUUID())).isTrue();
    }
}
