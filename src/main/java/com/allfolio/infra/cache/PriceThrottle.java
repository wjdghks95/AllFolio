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
 * 사용자당 시세 조회 Throttling(Task 022). INCR과 EXPIRE를 Lua 스크립트로 하나의 원자적 실행 단위로
 * 묶어 경쟁 상태 없이 고정 윈도우 카운터를 구현한다 — Redis에는 이 둘을 묶은 단일 명령이 원래
 * 존재하지 않는다(ROADMAP의 "Lettuce INCREX 지원 실측" 조사에서 확인).
 */
@Component
public class PriceThrottle {

    /**
     * count==1(이 윈도우의 첫 호출)일 때만 PEXPIRE를 건다. 매 호출마다 만료시간을 갱신하면 요청이
     * 끊이지 않는 한 윈도우가 계속 밀려 고정 윈도우가 아니라 슬라이딩처럼 동작하게 된다.
     * 초 단위 EXPIRE가 아닌 밀리초 단위 PEXPIRE를 쓰는 이유: window가 1초 미만이면 EXPIRE는
     * 인자가 0으로 잘려 키가 즉시 삭제돼버려(테스트로 실측 확인) 카운터가 절대 누적되지 않는다.
     */
    private static final String SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """;

    /** 컴파일된 스크립트는 상태가 없어 재사용 가능하다 — 호출마다 새로 만들 이유가 없다. */
    private static final RedisScript<Long> THROTTLE_SCRIPT = RedisScript.of(SCRIPT, Long.class);

    private static final Logger log = LoggerFactory.getLogger(PriceThrottle.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final PriceThrottleProperties properties;

    public PriceThrottle(StringRedisTemplate stringRedisTemplate, PriceThrottleProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    /**
     * 윈도우 내 요청 수가 한도 이하면 true(허용), 초과하면 false(거부).
     * Redis 장애 시에는 fail-open(true)한다 — Throttle은 부가 보호장치일 뿐이라, Redis가 죽었다고
     * 정상 시세 조회 요청까지 막아 가용성을 해치는 것보다 일시적으로 제한을 못 거는 쪽이 낫다.
     */
    public boolean tryAcquire(UUID userId) {
        String key = "throttle:price:" + userId;
        Long count;
        try {
            count = stringRedisTemplate.execute(THROTTLE_SCRIPT, List.of(key),
                    String.valueOf(properties.window().toMillis()));
        } catch (DataAccessException e) {
            log.warn("Redis Throttle 확인 실패 — 이번 요청은 허용 userId={}", userId, e);
            return true;
        }
        return count != null && count <= properties.limit();
    }
}
