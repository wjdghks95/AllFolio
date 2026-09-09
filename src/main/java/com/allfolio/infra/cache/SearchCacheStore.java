package com.allfolio.infra.cache;

import com.allfolio.domain.SearchCacheEntry;
import com.allfolio.domain.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 통합 종목 검색 결과 Redis read-through 캐시(Task 026 서브태스크 3). PriceCacheStore와 달리
 * fresh/stale 구분이 없다 — TTL(allfolio.search-cache.ttl)을 지나면 그냥 캐시 미스로 처리한다.
 */
@Component
public class SearchCacheStore {

    private static final Logger log = LoggerFactory.getLogger(SearchCacheStore.class);

    private final RedisTemplate<String, SearchCacheEntry> redisTemplate;
    private final SearchCacheProperties properties;

    public SearchCacheStore(RedisTemplate<String, SearchCacheEntry> searchCacheRedisTemplate,
            SearchCacheProperties properties) {
        this.redisTemplate = searchCacheRedisTemplate;
        this.properties = properties;
    }

    /** Redis 장애 시 캐시 미스(Optional.empty())로 간주한다 — 캐시는 최적화일 뿐이라 검색 자체를 막지 않는다. */
    public Optional<List<SearchResult>> find(String key) {
        SearchCacheEntry entry;
        try {
            entry = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("Redis 검색 캐시 조회 실패 — 캐시 미스로 처리 key={}", key, e);
            return Optional.empty();
        }
        return Optional.ofNullable(entry).map(SearchCacheEntry::results);
    }

    /** 저장 실패는 방금 받아온 검색 결과 자체와는 무관한 문제이므로 요청을 실패시키지 않고 무시한다. */
    public void save(String key, List<SearchResult> results) {
        try {
            SearchCacheEntry entry = new SearchCacheEntry(results, Instant.now());
            redisTemplate.opsForValue().set(key, entry, properties.ttl());
        } catch (DataAccessException e) {
            log.warn("Redis 검색 캐시 저장 실패 — 무시하고 계속 진행 key={}", key, e);
        }
    }
}
