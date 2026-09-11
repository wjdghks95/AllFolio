package com.allfolio.infra.cache;

import com.allfolio.domain.DailyBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * STOCK(국내+해외 공통) 일봉 원본 Redis 캐시(Task 028). {@link PriceCacheStore}의 fail-open 원칙을
 * 그대로 미러링한다 — Redis 조회 장애는 캐시 미스로 간주하고, 저장 실패는 로그만 남기고 무시한다
 * (캐시는 최적화일 뿐이라 Redis 장애가 시세/캔들 조회 자체를 막으면 안 된다).
 *
 * <p>{@link PriceCacheStore}와 달리 fresh/stale 2단계 판정을 이 클래스가 직접 하지 않는다 — 캔들은
 * 시계열(범위) 데이터라 "신선도"보다 "이 범위를 이미 커버했는가"가 더 중요한 판단 기준이고, 그 판단은
 * {@link CandleCacheEntry#oldestCovered()}/{@link CandleCacheEntry#newestCovered()}를 보는 호출자
 * (다음 태스크의 CandleService)의 책임이다. TTL 자체는 Redis EXPIRE로 관리한다({@link #save}).
 */
@Component
public class CandleCacheStore {

    private static final Logger log = LoggerFactory.getLogger(CandleCacheStore.class);

    private final RedisTemplate<String, CandleCacheEntry> redisTemplate;

    public CandleCacheStore(RedisTemplate<String, CandleCacheEntry> candleCacheRedisTemplate) {
        this.redisTemplate = candleCacheRedisTemplate;
    }

    public Optional<CandleCacheEntry> find(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        } catch (DataAccessException e) {
            log.warn("Redis 캔들 캐시 조회 실패 — 캐시 미스로 처리 key={}", key, e);
            return Optional.empty();
        }
    }

    public void save(String key, CandleCacheEntry entry, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, entry, ttl);
        } catch (DataAccessException e) {
            log.warn("Redis 캔들 캐시 저장 실패 — 무시하고 계속 진행 key={}", key, e);
        }
    }

    /**
     * 기존 캐시 항목에 더 과거 구간의 일봉({@code olderBars})을 병합한다(순수 함수, Redis I/O 없음).
     * 페이징으로 과거 데이터를 계속 불러올 때 호출자(CandleService)가 매번 이 결과를 {@link #save}로
     * 다시 저장하는 흐름을 전제한다.
     *
     * <ul>
     *   <li><b>날짜 기준 dedup:</b> 같은 날짜가 {@code existing}과 {@code olderBars} 양쪽에 있으면
     *       {@code existing} 쪽을 유지한다 — existing은 이미 한 번 이상 검증·병합을 거친 값이라
     *       새로 들어온 값보다 신뢰도가 낮지 않다는 전제다.</li>
     *   <li><b>총 범위 상한:</b> {@code maxHistoryStart}보다 과거인 날짜는(existing·olderBars 양쪽 모두)
     *       결과에서 제외한다 — 무한정 과거로 캐시가 확장되는 것을 막는다. 상한 때문에 하나라도 제외된
     *       날짜가 있으면(남은 캔들 유무와 무관하게) {@code oldestCovered}가 정확히 {@code maxHistoryStart}로
     *       수렴한다 — 제외됐다는 사실 자체가 "그 지점까지는 이미 조회를 시도했다"는 증거이기 때문이다.
     *       (제외된 날짜가 하나도 없다면 단순히 남은 캔들 중 가장 이른 날짜를 그대로 쓴다.)</li>
     *   <li><b>멱등성:</b> 같은 {@code olderBars}로 두 번 병합해도(두 번째 호출은 첫 번째 결과를
     *       {@code existing}으로 씀) 결과가 같다 — 두 번째 병합에서 {@code olderBars}의 모든 날짜가
     *       이미 existing에 있어 dedup 규칙(existing 우선)에 따라 값이 바뀌지 않기 때문이다.</li>
     * </ul>
     */
    public CandleCacheEntry mergeOlder(CandleCacheEntry existing, List<DailyBar> olderBars, LocalDate maxHistoryStart) {
        Map<LocalDate, DailyBar> merged = new TreeMap<>();
        // 상한 때문에 실제로 제외된 날짜가 하나라도 있었는지 — 있었다면 "그 지점까지는 이미 조회를
        // 시도했다"는 증거이므로, 남은 캔들 유무와 무관하게 oldestCovered가 상한으로 수렴해야 한다.
        boolean anyExcludedByCap = false;
        // olderBars를 먼저 채운 뒤 existing으로 덮어써야 같은 날짜에서 existing이 우선한다(dedup 규칙).
        for (DailyBar bar : olderBars) {
            if (!bar.date().isBefore(maxHistoryStart)) {
                merged.put(bar.date(), bar);
            } else {
                anyExcludedByCap = true;
            }
        }
        for (DailyBar bar : existing.bars()) {
            if (!bar.date().isBefore(maxHistoryStart)) {
                merged.put(bar.date(), bar);
            } else {
                anyExcludedByCap = true;
            }
        }

        List<DailyBar> mergedBars = List.copyOf(merged.values());
        LocalDate oldestCovered = (anyExcludedByCap || mergedBars.isEmpty())
                ? maxHistoryStart
                : mergedBars.get(0).date();
        return new CandleCacheEntry(mergedBars, oldestCovered, existing.newestCovered(), Instant.now());
    }
}
