package com.allfolio.infra.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 티커 단위 캔들 조회 락(cache stampede 방지, Task 028). 여러 요청이 동시에 같은 티커의 캐시 미스를
 * 겪으면 그중 하나만 벤더 API를 호출하게 하기 위한 짧은 상호 배제 락이다 — {@link PriceThrottle}의
 * Lua Script 기반 원자적 Redis 연산 패턴(스크립트를 {@code static final RedisScript}로 캐싱해 재사용)을
 * 재사용하지만, 목적이 카운터가 아닌 락이라 별도 컴포넌트로 분리한다(infra/price/CLAUDE.md "얕은 추상화
 * 회피" 원칙과 동일하게, PriceThrottle과 억지로 공통 인터페이스로 묶지 않는다).
 *
 * <p><b>폴링 대기 전략(다음 태스크의 CandleService가 따를 지침):</b> 락 획득에 실패한 요청은 즉시
 * 재시도하지 않고 짧게 대기한 뒤 캐시를 재조회해야 한다(락을 가진 쪽이 그 사이 벤더를 호출해 캐시를
 * 채워줬을 것이기 때문). 폴링 간격은 50~100ms 권장(그보다 짧으면 Redis에 불필요한 부하, 그보다 길면
 * 체감 지연 증가), 최대 대기 시간은 {@link CandleCacheProperties#rangeLockTtl}과 동일하게 맞춘다 —
 * 락을 쥔 쪽이 정상 응답했다면 그 시간 안에 반드시 락이 해제되거나(성공 시 {@link #unlock}) TTL 만료로
 * 자연 해제되므로, 그보다 오래 기다릴 이유가 없다(무한 대기 방지).
 *
 * <p><b>Redis 장애 시 fail-open:</b> 락 자체는 최적화(중복 벤더 호출 방지)일 뿐이므로, Redis 장애로
 * 락을 걸 수 없으면 락을 획득한 것처럼 통과시킨다({@link #tryLock} true 반환) — 그래야 캔들 조회
 * 자체가 Redis 장애로 막히지 않는다(중복 호출은 늘 수 있지만 가용성을 우선한다, 루트 CLAUDE.md
 * "Redis 장애 시 DB 직접 조회" 원칙과 동일한 방향).
 */
@Component
public class CandleRangeLock {

    private static final String LOCK_KEY_PREFIX = "lock:candles:STOCK:";

    // 자기 소유 확인 후 DEL(PriceThrottle의 원자성 패턴과 동일 이유) — GET으로 토큰을 먼저 확인하고
    // DEL하는 두 단계를 분리하면 그 사이에 TTL이 만료되고 다른 프로세스가 같은 키로 새 락을 잡았을 때
    // 남의 락을 지워버릴 수 있다(race condition). Lua로 확인+삭제를 원자적으로 묶는다.
    private static final String UNLOCK_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            else
              return 0
            end
            """;

    private static final RedisScript<Long> UNLOCK = RedisScript.of(UNLOCK_SCRIPT, Long.class);

    private static final Logger log = LoggerFactory.getLogger(CandleRangeLock.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final CandleCacheProperties properties;

    // 이 프로세스(JVM)가 실제로 획득한 락의 소유 토큰을 기억해둔다 — unlock()이 자기 소유가 아닌 락을
    // (TTL 만료 후 다른 요청이 새로 잡은 락을) 실수로 지우지 않도록 한다.
    private final ConcurrentHashMap<String, String> ownedTokens = new ConcurrentHashMap<>();

    public CandleRangeLock(StringRedisTemplate stringRedisTemplate, CandleCacheProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    /**
     * {@code SET key token NX EX <rangeLockTtl>}로 락을 시도한다. 동시에 여러 요청이 같은
     * {@code tickerKey}로 호출해도 정확히 하나만 true를 반환한다.
     */
    public boolean tryLock(String tickerKey) {
        String key = LOCK_KEY_PREFIX + tickerKey;
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, token, properties.rangeLockTtl());
        } catch (DataAccessException e) {
            log.warn("Redis 캔들 락 획득 실패 — fail-open으로 통과 tickerKey={}", tickerKey, e);
            return true;
        }
        if (Boolean.TRUE.equals(acquired)) {
            ownedTokens.put(key, token);
            return true;
        }
        return false;
    }

    /** 이 프로세스가 {@link #tryLock}으로 실제 획득한 락만 해제한다 — 획득 실패했던 호출은 아무 것도 하지 않는다. */
    public void unlock(String tickerKey) {
        String key = LOCK_KEY_PREFIX + tickerKey;
        String token = ownedTokens.remove(key);
        if (token == null) {
            return;
        }
        try {
            stringRedisTemplate.execute(UNLOCK, List.of(key), token);
        } catch (DataAccessException e) {
            // 저장 실패와 동일하게 무시한다 — 최악의 경우 TTL 만료로 자연 해제되므로 락이 영구히 남지 않는다.
            log.warn("Redis 캔들 락 해제 실패 — TTL 만료로 자연 해제됨 tickerKey={}", tickerKey, e);
        }
    }
}
