package com.allfolio.infra.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 사용자당 COIN 캔들 조회 요청 Throttling(ROADMAP Task 031 서브태스크 4 — 실측으로 확인된 업비트
 * 남용을 차단). PriceThrottle/SearchThrottle과 동일한 Lua INCR+PEXPIRE 스크립트를 쓰되 Redis 키를
 * {@code throttle:candle:{userId}}로 분리하고, CandleThrottleProperties(3건/1초)를 주입한다.
 * PriceThrottle에 keyPrefix만 다르게 위임하지 않고 별도 @Component로 만든 이유는 SearchThrottle과
 * 동일하다 — 공유 컴포넌트에서 PriceThrottleProperties가 실제 적용되는 함정을 막기 위함이다
 * (infra/cache/CLAUDE.md 참고).
 */
@Component
public class CandleThrottle {

    private static final String SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """;

    private static final RedisScript<Long> THROTTLE_SCRIPT = RedisScript.of(SCRIPT, Long.class);

    private static final Logger log = LoggerFactory.getLogger(CandleThrottle.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final CandleThrottleProperties properties;

    public CandleThrottle(StringRedisTemplate stringRedisTemplate, CandleThrottleProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    /**
     * 윈도우 내 요청 수가 한도 이하면 true(허용), 초과하면 false(거부).
     * Redis 장애 시에는 fail-open(true)한다 — Throttle은 부가 보호장치일 뿐이라, Redis가 죽었다고
     * 정상 요청까지 막아 가용성을 해치는 것보다 일시적으로 제한을 못 거는 쪽이 낫다.
     */
    public boolean tryAcquire(UUID userId) {
        String key = "throttle:candle:" + userId;
        Long count;
        try {
            count = stringRedisTemplate.execute(THROTTLE_SCRIPT, List.of(key),
                    String.valueOf(properties.window().toMillis()));
        } catch (DataAccessException e) {
            log.warn("Redis 캔들 Throttle 확인 실패 — 이번 요청은 허용 userId={}", userId, e);
            return true;
        }
        return count != null && count <= properties.limit();
    }
}
