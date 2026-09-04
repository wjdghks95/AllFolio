package com.allfolio.infra.cache;

import com.allfolio.domain.Price;
import com.allfolio.domain.PricedQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 시세 Redis read-through 캐시(Task 022). 캐시 키는 시장 데이터 식별자 기준(사용자 무관)이며
 * PriceService가 조립해 넘긴다 — infra/price/CLAUDE.md의 "클라이언트별 독립 구현" 원칙에 따라
 * 이 컴포넌트는 infra/price와 분리된 별도 계층이다.
 */
@Component
public class PriceCacheStore {

    private static final Logger log = LoggerFactory.getLogger(PriceCacheStore.class);

    private static final String FAILED_KEY_PREFIX = "price:failed:";

    private final RedisTemplate<String, PriceCacheEntry> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final PriceCacheProperties properties;

    public PriceCacheStore(RedisTemplate<String, PriceCacheEntry> priceCacheRedisTemplate,
            StringRedisTemplate stringRedisTemplate, PriceCacheProperties properties) {
        this.redisTemplate = priceCacheRedisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    /**
     * freshTtl 이내면 quote.stale()=false(외부 API 호출 불필요), 아니어도 값이 있으면 stale=true로
     * 폴백용으로 함께 반환한다. Redis 장애 시 캐시 미스(Optional.empty())로 간주한다 — 캐시는
     * 최적화일 뿐이라, Redis가 죽었다고 시세 조회 자체(외부 API 직접 호출)까지 500으로 막을 이유가 없다.
     */
    public Optional<PricedQuote> find(String key, Duration freshTtl) {
        PriceCacheEntry entry;
        try {
            entry = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("Redis 캐시 조회 실패 — 캐시 미스로 처리 key={}", key, e);
            return Optional.empty();
        }
        if (entry == null) {
            return Optional.empty();
        }
        boolean fresh = Duration.between(entry.cachedAt(), Instant.now()).compareTo(freshTtl) < 0;
        Price price = new Price(entry.amount(), entry.currency(), entry.asOf());
        return Optional.of(new PricedQuote(price, !fresh));
    }

    /**
     * Redis TTL은 staleCeiling(신선도와 무관한 최종 폴백 한계)으로 설정한다.
     * 쓰기 실패는 방금 받아온 시세 응답 자체와는 무관한 문제이므로 요청을 실패시키지 않고 무시한다.
     */
    public void save(String key, Price price) {
        try {
            PriceCacheEntry entry = new PriceCacheEntry(price.amount(), price.currency(), price.asOf(), Instant.now());
            redisTemplate.opsForValue().set(key, entry, properties.staleCeiling());
        } catch (DataAccessException e) {
            log.warn("Redis 캐시 저장 실패 — 무시하고 계속 진행 key={}", key, e);
        }
    }

    /**
     * 시세 조회 실패(존재하지 않는 티커 등)를 짧은 TTL로 부정 캐싱한다(Task 023 Major 2). 실제 시세 데이터가
     * 아니라 존재 여부만 필요하므로 PriceCacheEntry 직렬화 대신 StringRedisTemplate로 별도 키 prefix에
     * 아무 문자열이나 저장한다. 저장 실패는 조용히 무시한다 — 부정 캐싱은 최적화일 뿐이라 Redis 장애가
     * 시세 조회 자체를 막으면 안 된다.
     */
    public void markFailed(String key) {
        try {
            stringRedisTemplate.opsForValue().set(FAILED_KEY_PREFIX + key, "1", properties.negativeTtl());
        } catch (DataAccessException e) {
            log.warn("Redis 실패 마커 저장 실패 — 무시하고 계속 진행 key={}", key, e);
        }
    }

    /** 마커 조회 실패는 fail-open으로 "실패 이력 없음"(false)으로 간주한다 — save()/find()와 동일한 원칙. */
    public boolean hasRecentFailure(String key) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(FAILED_KEY_PREFIX + key));
        } catch (DataAccessException e) {
            log.warn("Redis 실패 마커 조회 실패 — 실패 이력 없음으로 처리 key={}", key, e);
            return false;
        }
    }
}
