package com.allfolio.domain.service;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Candle;
import com.allfolio.domain.CandleAggregator;
import com.allfolio.domain.CandleInterval;
import com.allfolio.domain.DailyBar;
import com.allfolio.domain.PrecisionScale;
import com.allfolio.domain.exception.AssetNotFoundException;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.InvalidCandleQueryException;
import com.allfolio.domain.exception.PriceRateLimitExceededException;
import com.allfolio.domain.exception.PriceUnavailableException;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.infra.cache.CandleCacheEntry;
import com.allfolio.infra.cache.CandleCacheProperties;
import com.allfolio.infra.cache.CandleCacheStore;
import com.allfolio.infra.cache.CandleRangeLock;
import com.allfolio.infra.cache.CandleThrottle;
import com.allfolio.infra.price.StockPriceClient;
import com.allfolio.infra.price.TwelveDataClient;
import com.allfolio.infra.price.UpbitPriceClient;
import com.allfolio.infra.sse.CandleSubscriptionKey;
import com.allfolio.web.dto.CandleBarResponse;
import com.allfolio.web.dto.CandleSeriesResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code GET /v1/assets/{id}/candles} 오케스트레이션(docs/ROADMAP.md Task 028). COIN/STOCK의
 * 캔들 조회 전략이 근본적으로 다르다:
 *
 * <ul>
 *   <li><b>COIN — 캐시 없는 패스스루.</b> 업비트는 호출 한도가 여유로워(REST 30 req/s) 이번 범위에선
 *       캐싱 없이 요청마다 직접 조회한다(과설계 방지, 나중에 병목이 실측되면 재검토). 년봉은
 *       업비트가 엔드포인트를 제공하지 않아 월봉을 받아 {@link UpbitPriceClient#aggregateYears}로
 *       집계한다.</li>
 *   <li><b>STOCK — {@link CandleCacheStore}/{@link CandleRangeLock}/{@link CandleAggregator}로
 *       오케스트레이션.</b> 국내(공공데이터포털)·해외(Twelve Data) 일봉 원본을 Redis에 캐싱하고,
 *       주/월/년봉은 캐시된 일봉을 집계해서 만든다(벤더에 별도 요청하지 않음 — 무료 API 호출 한도를
 *       불필요하게 소진하지 않기 위함, {@code infra/price/CLAUDE.md}·twelvedata-api 에이전트 문서가
 *       이미 확정한 원칙).</li>
 * </ul>
 *
 * <p>메서드 레벨에 {@code @Transactional}을 붙이지 않는다 — {@link PriceService#getPrice}와 동일한
 * 이유로, 소유권 조회(단건 SELECT) 뒤에 외부 API 호출(초 단위 소요)이 이어질 수 있는데 트랜잭션을
 * 두르면 Hikari 커넥션을 그 동안 붙들게 된다.
 */
@Service
public class CandleService {

    /**
     * 업비트 캔들 API의 {@code count} 파라미터 실측 상한(2026-09-11, curl 직접 호출로
     * {@code count=201} 요청 시 실제로는 200건만 반환됨을 확인). 이 값만큼 요청해 정확히 이 값이
     * 돌아오면(페이지가 꽉 찼으면) 더 과거 데이터가 남아있을 가능성이 높다고 판단한다
     * ({@link CandleSeriesResponse#hasMoreHistory} 근사 판정 근거).
     */
    private static final int UPBIT_PAGE_SIZE = 200;

    /**
     * Twelve Data {@code /time_series}의 {@code outputsize} 실측 상한(2026-09-11 curl 검증,
     * {@code .claude/agents/twelvedata-api.md} 참고) — 5000건은 약 19년치라 이 프로젝트의
     * {@code maxHistoryYears}(10년) 캡보다 넉넉하다. credit 소비가 {@code outputsize} 크기와
     * 무관하게 요청 1회당 1로 고정임이 실측 확인됐으므로, 매번 최대값을 요청해 한 번의 호출로 캡
     * 전체를 커버한다 — 그러면 {@link CandleCacheStore#mergeOlder}가 캡을 넘는 과거 데이터를 잘라내
     * 첫 호출만으로 {@code oldestCovered}가 {@code maxHistoryStart}에 도달한다(추가 확장 호출이
     * 사실상 불필요해짐). Twelve Data는 begin/end 같은 임의 과거 구간 파라미터를 제공하지 않아
     * (항상 "최근 N건"만 응답), 이 값보다 작게 요청해도 더 이전 구간을 노려 받을 수 없다 — 그래서
     * 최대값 고정이 유일하게 의미 있는 선택이다.
     */
    private static final int TWELVEDATA_MAX_OUTPUTSIZE = 5000;

    /**
     * STOCK 캡·범위 계산에 쓰는 기준 타임존(Task 028 code-reviewer m2 실측 수정). UTC로 배포된
     * 서버에서 {@code LocalDate.now()}(시스템 기본 타임존)를 그대로 쓰면 한국 자정 전후 몇 시간
     * 동안 캡·{@code end} 계산이 하루 밀릴 수 있다 — {@link StockPriceClient}가 이미 쓰는 KST 상수
     * 패턴과 동일하게 명시한다.
     */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * {@link CandleRangeLock} 획득 실패 시 폴링 간격. 락 Javadoc의 권장 범위(50~100ms) 중간값 —
     * 그보다 짧으면 Redis에 불필요한 부하, 길면 체감 지연이 늘어난다.
     */
    private static final Duration LOCK_POLL_INTERVAL = Duration.ofMillis(75);

    private final AssetRepository assetRepository;
    private final UpbitPriceClient upbitPriceClient;
    private final StockPriceClient stockPriceClient;
    private final TwelveDataClient twelveDataClient;
    private final CandleCacheStore candleCacheStore;
    private final CandleRangeLock candleRangeLock;
    private final CandleCacheProperties candleCacheProperties;
    private final CandleThrottle candleThrottle;

    public CandleService(AssetRepository assetRepository, UpbitPriceClient upbitPriceClient,
            StockPriceClient stockPriceClient, TwelveDataClient twelveDataClient,
            CandleCacheStore candleCacheStore, CandleRangeLock candleRangeLock,
            CandleCacheProperties candleCacheProperties, CandleThrottle candleThrottle) {
        this.assetRepository = assetRepository;
        this.upbitPriceClient = upbitPriceClient;
        this.stockPriceClient = stockPriceClient;
        this.twelveDataClient = twelveDataClient;
        this.candleCacheStore = candleCacheStore;
        this.candleRangeLock = candleRangeLock;
        this.candleCacheProperties = candleCacheProperties;
        this.candleThrottle = candleThrottle;
    }

    /**
     * 소유권 검증은 {@link PriceService}·{@link TransactionService}와 동일하게 404
     * {@code ASSET_NOT_FOUND}로 통일한다(403이면 "그 ID가 존재한다"는 사실이 새어 나간다).
     * CASH 자산은 시세 자체가 없어(avg_price=1 고정) 캔들 개념도 없으므로 기존
     * {@link PriceUnavailableException}(400 {@code PRICE_NOT_APPLICABLE})을 그대로 재사용한다.
     */
    public CandleSeriesResponse getCandles(UUID userId, UUID assetId, String intervalParam, String beforeParam) {
        Asset asset = assetRepository.findByIdAndUser_Id(assetId, userId)
                .orElseThrow(() -> new AssetNotFoundException("해당 자산을 찾을 수 없습니다."));
        CandleInterval interval = CandleInterval.from(intervalParam);
        if (asset.getAssetType() == AssetType.STOCK && interval.isMinute()) {
            throw new InvalidCandleQueryException("STOCK 자산은 분봉을 지원하지 않습니다: " + intervalParam);
        }

        return switch (asset.getAssetType()) {
            case COIN -> coinCandles(userId, asset, interval, beforeParam);
            case STOCK -> stockCandles(asset, interval, beforeParam);
            case CASH -> throw new PriceUnavailableException("CASH 자산은 캔들 조회 대상이 아닙니다.");
        };
    }

    /**
     * {@code GET /v1/assets/{id}/candles/stream}(Task 028 SSE) 구독 진입점. {@link #getCandles}와
     * 동일한 소유권 검증(404 {@code ASSET_NOT_FOUND})을 재사용하되, 실시간 스트리밍은 COIN에만 제공한다
     * (STOCK은 캐시된 EOD 데이터라 "실시간"이라는 전제 자체가 성립하지 않는다).
     */
    public CandleSubscriptionKey resolveCoinSubscriptionKey(UUID userId, UUID assetId, String intervalParam) {
        Asset asset = assetRepository.findByIdAndUser_Id(assetId, userId)
                .orElseThrow(() -> new AssetNotFoundException("해당 자산을 찾을 수 없습니다."));
        if (asset.getAssetType() != AssetType.COIN) {
            throw new InvalidCandleQueryException("COIN 자산만 실시간 스트리밍을 지원합니다.");
        }
        CandleInterval interval = CandleInterval.from(intervalParam);
        return new CandleSubscriptionKey(asset.getTicker(), asset.getCurrency(), interval);
    }

    /**
     * {@link com.allfolio.infra.sse.CandlePushScheduler}(Task 028)가 재사용하는 진입점 — 최신 캔들
     * 1건만 필요할 때 쓴다. {@link #fetchCoinCandles}(REST {@code GET /candles}가 쓰는 동일한 라우팅
     * switch)를 그대로 재사용해, 벤더 호출 경로가 두 곳에서 어긋나는 드리프트를 막는다.
     *
     * <p>YEAR interval은 count=1로는 해당 연도의 월봉을 다 못 채워 부분 집계가 실제와 달라질 수 있으므로
     * {@link #UPBIT_PAGE_SIZE}(200) 그대로 요청한다. 그 외 interval은 "최신 캔들 1건"만 필요하므로
     * count=1로 요청해 폴링마다 불필요한 트래픽이 늘어나지 않게 한다.
     */
    public Candle fetchLatestCoinCandle(String ticker, String currency, CandleInterval interval) {
        int count = interval == CandleInterval.YEAR ? UPBIT_PAGE_SIZE : 1;
        CoinFetch fetch = fetchCoinCandles(ticker, currency, interval, null, count);
        if (fetch.display().isEmpty()) {
            throw new ExternalPriceApiException("업비트에서 캔들 데이터를 받지 못했습니다: " + ticker);
        }
        return fetch.display().get(0);
    }

    // ---------------------------------------------------------------------
    // COIN — 캐시 없는 패스스루
    // ---------------------------------------------------------------------

    /**
     * COIN candles는 캐시가 전혀 없는 순수 패스스루라(위 클래스 Javadoc), 요청 1건이 곧 업비트 호출
     * 1건이다 — 모든 요청에 균일하게 Throttle을 적용한다("캐시 히트는 소모하지 않는다"는 PriceThrottle의
     * 전제가 여기선 성립하지 않는다). 실측(30 VUs·30초 부하로 업비트 429·우리 서버 503 98%, ROADMAP
     * Task 031 서브태스크 4)으로 확인된 남용을 차단한다. {@link #fetchLatestCoinCandle}(SSE
     * CandlePushScheduler 전용)은 이 검사 대상이 아니다 — SSE 스트리밍은 이번 태스크 범위 밖이다.
     */
    private CandleSeriesResponse coinCandles(UUID userId, Asset asset, CandleInterval interval, String beforeParam) {
        if (!candleThrottle.tryAcquire(userId)) {
            throw new PriceRateLimitExceededException("캔들 조회 요청이 너무 잦습니다. 잠시 후 다시 시도하세요.");
        }
        Instant before = parseBeforeInstant(beforeParam);
        CoinFetch fetch = fetchCoinCandles(asset.getTicker(), asset.getCurrency(), interval, before);

        // 요청 페이지 크기(UPBIT_PAGE_SIZE)를 꽉 채워 받았으면 그 너머에 더 과거 데이터가 남아있을
        // 가능성이 높다고 근사한다 — 업비트는 마켓의 전체 이력 길이를 알려주는 별도 필드가 없다.
        boolean hasMoreHistory = fetch.raw().size() >= UPBIT_PAGE_SIZE;
        int scale = PrecisionScale.scaleFor(AssetType.COIN, asset.getCurrency());
        List<CandleBarResponse> bars = fetch.display().stream().map(candle -> toBarResponse(candle, scale)).toList();
        return new CandleSeriesResponse(bars, hasMoreHistory);
    }

    private CoinFetch fetchCoinCandles(String ticker, String currency, CandleInterval interval, Instant before) {
        return fetchCoinCandles(ticker, currency, interval, before, UPBIT_PAGE_SIZE);
    }

    /** count를 인자화한 이유는 {@link #fetchLatestCoinCandle} Javadoc 참고 (SSE는 최신 1건만 필요). */
    private CoinFetch fetchCoinCandles(String ticker, String currency, CandleInterval interval, Instant before, int count) {
        return switch (interval) {
            case MINUTE1, MINUTE3, MINUTE5, MINUTE10, MINUTE15, MINUTE30, MINUTE60, MINUTE240 -> {
                int unit = interval.minuteUnit();
                List<Candle> raw = before == null
                        ? upbitPriceClient.getMinuteCandles(ticker, currency, unit, count)
                        : upbitPriceClient.getMinuteCandles(ticker, currency, unit, count, before);
                yield new CoinFetch(raw, raw);
            }
            case DAY -> {
                List<Candle> raw = before == null
                        ? upbitPriceClient.getDayCandles(ticker, currency, count)
                        : upbitPriceClient.getDayCandles(ticker, currency, count, before);
                yield new CoinFetch(raw, raw);
            }
            case WEEK -> {
                List<Candle> raw = before == null
                        ? upbitPriceClient.getWeekCandles(ticker, currency, count)
                        : upbitPriceClient.getWeekCandles(ticker, currency, count, before);
                yield new CoinFetch(raw, raw);
            }
            case MONTH -> {
                List<Candle> raw = before == null
                        ? upbitPriceClient.getMonthCandles(ticker, currency, count)
                        : upbitPriceClient.getMonthCandles(ticker, currency, count, before);
                yield new CoinFetch(raw, raw);
            }
            // 업비트는 년봉 엔드포인트가 없다 — 월봉을 받아 집계한다(UpbitPriceClient.aggregateYears Javadoc).
            case YEAR -> {
                List<Candle> raw = before == null
                        ? upbitPriceClient.getMonthCandles(ticker, currency, count)
                        : upbitPriceClient.getMonthCandles(ticker, currency, count, before);
                yield new CoinFetch(raw, UpbitPriceClient.aggregateYears(raw));
            }
        };
    }

    /** raw는 hasMoreHistory 판정용 원본(월봉 포함 가능), display는 응답에 실제로 나갈 집계 결과. */
    private record CoinFetch(List<Candle> raw, List<Candle> display) {
    }

    // ---------------------------------------------------------------------
    // STOCK — CandleCacheStore/CandleRangeLock/CandleAggregator 오케스트레이션
    // ---------------------------------------------------------------------

    private CandleSeriesResponse stockCandles(Asset asset, CandleInterval interval, String beforeParam) {
        LocalDate before = parseBeforeDate(beforeParam);
        LocalDate maxHistoryStart = LocalDate.now(KST).minusYears(candleCacheProperties.maxHistoryYears());
        String cacheKey = candleCacheKeyFor(asset);

        CandleCacheEntry entry = ensureCoverage(asset, cacheKey, maxHistoryStart);

        List<DailyBar> filtered = entry.bars().stream()
                .filter(bar -> before == null || bar.date().isBefore(before))
                .toList();

        List<DailyBar> aggregated = switch (interval) {
            case DAY -> filtered.stream().sorted(Comparator.comparing(DailyBar::date).reversed()).toList();
            case WEEK -> CandleAggregator.aggregateWeeks(filtered);
            case MONTH -> CandleAggregator.aggregateMonths(filtered);
            case YEAR -> CandleAggregator.aggregateYears(filtered);
            // STOCK+분봉은 getCandles()에서 이미 400으로 걸러진다 — 여기 도달하면 그 가드가 깨진
            // 버그다. switch expression 문법상(enum 전량 커버 요구) 케이스 자체는 있어야 한다.
            case MINUTE1, MINUTE3, MINUTE5, MINUTE10, MINUTE15, MINUTE30, MINUTE60, MINUTE240 ->
                    throw new IllegalStateException("STOCK 자산은 분봉을 지원하지 않습니다: " + interval);
        };

        // STOCK은 캐시 미스 시 KRW·USD 둘 다 벤더가 줄 수 있는 최대 범위(캡 전체)를 한 번에
        // 요청하는 전략이다(fetchVendorBars 참고) — 그래서 캐시가 한 번 채워지면 그 시점에 이미
        // 벤더가 줄 수 있는 전부를 받은 것이고, 원천적으로 더 페이징할 과거 데이터가 없다. 예전엔
        // entry.oldestCovered()가 캡에 도달했는지로 판정했는데, KRW·USD 둘 다 최초 1회 호출로 캡
        // 이전 구간 자체를 요청하지 않는 케이스(예: 상장 이력이 캡보다 짧은 종목)에서는 캡에
        // 도달하지 못했을 뿐 실제로는 더 받을 데이터가 없어 hasMoreHistory가 영구히 true로 남는
        // 버그였다(Task 028 code-reviewer M2 실측). 항상 false로 고정한다.
        boolean hasMoreHistory = false;
        int scale = PrecisionScale.scaleFor(AssetType.STOCK, asset.getCurrency());
        List<CandleBarResponse> bars = aggregated.stream().map(bar -> toBarResponse(bar, scale)).toList();
        return new CandleSeriesResponse(bars, hasMoreHistory);
    }

    /**
     * 캐시가 있으면 그대로 반환하고, 없으면 벤더 호출로 채운 뒤 반환한다. STOCK은 캐시 미스 시
     * KRW·USD 둘 다 벤더가 줄 수 있는 최대 범위를 한 번에 요청하므로(fetchVendorBars 참고), 캐시가
     * 한 번이라도 채워지면 그 자체로 이미 충분하다 — "부분적으로만 채워졌다"는 상태 자체가 존재하지
     * 않는다(Task 028 code-reviewer M2. 예전에는 {@code before} 페이징이 캐시를 더 과거로 확장할 수
     * 있다고 가정했으나, KRW 요청도 항상 {@code begin=maxHistoryStart}로 캡 전체를 요청하므로 재호출은
     * 항상 같은 결과만 돌려줘 확장이 무의미했다).
     *
     * <p>cache stampede 방지를 위해 {@link CandleRangeLock}으로 티커 단위 상호 배제를 건다 — 락
     * 획득에 실패하면(다른 요청이 이미 채우는 중) {@link CandleRangeLock} Javadoc 지침대로 짧게 폴링
     * 대기한 뒤 캐시를 재조회하고, 그마저도 시간 안에 채워지지 않으면 fail-open으로 직접 조회한다
     * (가용성 우선, 중복 벤더 호출 한 번 늘어나는 것을 감수).
     */
    private CandleCacheEntry ensureCoverage(Asset asset, String cacheKey, LocalDate maxHistoryStart) {
        Optional<CandleCacheEntry> cached = candleCacheStore.find(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        String lockToken = candleRangeLock.tryLock(cacheKey);
        if (lockToken != null) {
            try {
                // 락 대기 없이 곧장 획득했더라도, 그 사이 다른 프로세스가 이미 채웠을 수 있어 재조회한다.
                Optional<CandleCacheEntry> latest = candleCacheStore.find(cacheKey);
                if (latest.isPresent()) {
                    return latest.get();
                }
                CandleCacheEntry updated = fetchAndMerge(asset, maxHistoryStart);
                candleCacheStore.save(cacheKey, updated, candleCacheProperties.stockDailyTtl());
                return updated;
            } finally {
                candleRangeLock.unlock(cacheKey, lockToken);
            }
        }

        Optional<CandleCacheEntry> polled = pollForCache(cacheKey);
        if (polled.isPresent()) {
            return polled.get();
        }
        // 대기 시간 안에도 채워지지 않으면 fail-open으로 직접 조회한다 — 진입 직전에 캐시를 한 번 더
        // 조회해 락 보유자가 마지막 순간에 채워둔 최신 엔트리를 놓치지 않는다(Task 028 code-reviewer
        // m5 — 안 그러면 이 메서드 진입 시점의 오래된 cached 스냅샷 기준으로 병합·저장해, 락 보유자가
        // 방금 채운 최신 엔트리를 덮어쓸 위험이 있다). 이 결과도 캐시에 저장해야 바로 다음 요청부터는
        // 다시 락 대기를 겪지 않는다(저장을 빼먹으면 락 경합 중인 모든 요청이 매번 벤더를 다시
        // 호출하게 된다).
        Optional<CandleCacheEntry> freshest = candleCacheStore.find(cacheKey);
        if (freshest.isPresent()) {
            return freshest.get();
        }
        CandleCacheEntry updated = fetchAndMerge(asset, maxHistoryStart);
        candleCacheStore.save(cacheKey, updated, candleCacheProperties.stockDailyTtl());
        return updated;
    }

    private Optional<CandleCacheEntry> pollForCache(String cacheKey) {
        long deadlineNanos = System.nanoTime() + candleCacheProperties.rangeLockTtl().toNanos();
        while (System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(LOCK_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Optional<CandleCacheEntry> latest = candleCacheStore.find(cacheKey);
            if (latest.isPresent()) {
                return latest;
            }
        }
        return candleCacheStore.find(cacheKey);
    }

    /**
     * 캐시 미스 상태에서만 호출된다({@link #ensureCoverage} 참고 — 캐시가 조금이라도 있으면 그대로
     * 충분하다고 보고 여기까지 오지 않는다) — 그래서 항상 빈 엔트리를 기준({@code base})으로
     * {@link CandleCacheStore#mergeOlder}를 호출한다.
     */
    private CandleCacheEntry fetchAndMerge(Asset asset, LocalDate maxHistoryStart) {
        List<DailyBar> fetched = fetchVendorBars(asset, maxHistoryStart);
        LocalDate today = LocalDate.now(KST);
        CandleCacheEntry base = new CandleCacheEntry(List.of(), today, today, Instant.now());
        return candleCacheStore.mergeOlder(base, fetched, maxHistoryStart);
    }

    private List<DailyBar> fetchVendorBars(Asset asset, LocalDate maxHistoryStart) {
        if ("USD".equals(asset.getCurrency())) {
            return twelveDataClient.getDailySeries(asset.getTicker(), TWELVEDATA_MAX_OUTPUTSIZE);
        }
        // 국내(공공데이터포털)는 begin/end 범위 조회를 지원한다(StockPriceClient.getDailySeries 실측
        // 확인 — 10년 범위도 단일 페이지로 응답). 캐시 미스 시에만 호출되므로 항상 캡 전체
        // (maxHistoryStart~오늘)를 한 번에 요청한다(Task 028 code-reviewer M2 — begin을 캡보다 좁게
        // 잡는 "확장" 재호출은 항상 begin=maxHistoryStart였던 최초 호출의 부분집합이라 새 데이터를
        // 받을 수 없어 제거했다).
        return stockPriceClient.getDailySeries(asset.getTicker(), maxHistoryStart, LocalDate.now(KST));
    }

    /** PriceService.cacheKeyFor(STOCK)와 동일한 컨벤션 — KRW는 접미사 없음(하위 호환), 그 외는 통화 접미사. */
    private String candleCacheKeyFor(Asset asset) {
        return "candle:STOCK:" + asset.getTicker()
                + ("KRW".equals(asset.getCurrency()) ? "" : ":" + asset.getCurrency());
    }

    // ---------------------------------------------------------------------
    // 공통 파싱·변환
    // ---------------------------------------------------------------------

    /** COIN의 before는 캔들 구간 시작 시각(Instant, ISO-8601)이다 — UpbitPriceClient#getDayCandles 등의 {@code to} 인자와 동일 의미. */
    private Instant parseBeforeInstant(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new InvalidCandleQueryException("잘못된 before 값입니다(ISO-8601 Instant 형식이어야 합니다): " + raw);
        }
    }

    /** STOCK의 before는 거래일 날짜(LocalDate, ISO-8601)다 — DailyBar#date()와 동일 표현. */
    private LocalDate parseBeforeDate(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new InvalidCandleQueryException("잘못된 before 값입니다(ISO-8601 날짜 형식이어야 합니다): " + raw);
        }
    }

    private CandleBarResponse toBarResponse(Candle candle, int scale) {
        return new CandleBarResponse(
                candle.candleAt().toString(),
                scaled(candle.open(), scale),
                scaled(candle.high(), scale),
                scaled(candle.low(), scale),
                scaled(candle.close(), scale));
    }

    private CandleBarResponse toBarResponse(DailyBar bar, int scale) {
        return new CandleBarResponse(
                bar.date().toString(),
                scaled(bar.open(), scale),
                scaled(bar.high(), scale),
                scaled(bar.low(), scale),
                scaled(bar.close(), scale));
    }

    private String scaled(BigDecimal amount, int scale) {
        return amount.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }
}
