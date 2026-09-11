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
 * 사용자당 종목 검색 요청 Throttling(Task 026). PriceThrottle과 동일한 Lua INCR+PEXPIRE 스크립트를
 * 쓰되 Redis 키를 {@code throttle:search:{userId}}로 분리하고, SearchThrottleProperties(60건/10초)를
 * 주입한다. PriceThrottle에 keyPrefix만 다르게 위임하는 방식을 쓰지 않고 별도 @Component로 만든
 * 이유는, 공유 컴포넌트에서 SearchThrottleProperties가 아닌 PriceThrottleProperties가 실제 적용되는
 * 함정을 막기 위함이다.
 */
@Component
public class SearchThrottle {

    private static final String SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """;

    private static final RedisScript<Long> THROTTLE_SCRIPT = RedisScript.of(SCRIPT, Long.class);

    private static final Logger log = LoggerFactory.getLogger(SearchThrottle.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final SearchThrottleProperties properties;

    public SearchThrottle(StringRedisTemplate stringRedisTemplate, SearchThrottleProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    /**
     * 윈도우 내 요청 수가 한도 이하면 true(허용), 초과하면 false(거부).
     * Redis 장애 시에는 fail-open(true)한다 — Throttle은 부가 보호장치일 뿐이라, Redis가 죽었다고
     * 정상 요청까지 막아 가용성을 해치는 것보다 일시적으로 제한을 못 거는 쪽이 낫다.
     */
    public boolean tryAcquire(UUID userId) {
        String key = "throttle:search:" + userId;
        Long count;
        try {
            count = stringRedisTemplate.execute(THROTTLE_SCRIPT, List.of(key),
                    String.valueOf(properties.window().toMillis()));
        } catch (DataAccessException e) {
            log.warn("Redis 검색 Throttle 확인 실패 — 이번 요청은 허용 userId={}", userId, e);
            return true;
        }
        return count != null && count <= properties.limit();
    }
}
