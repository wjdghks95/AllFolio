package com.allfolio;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Candle;
import com.allfolio.domain.DailyBar;
import com.allfolio.domain.User;
import com.allfolio.domain.exception.AssetNotFoundException;
import com.allfolio.domain.exception.InvalidCandleQueryException;
import com.allfolio.domain.exception.PriceUnavailableException;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.domain.service.CandleService;
import com.allfolio.infra.cache.CandleCacheEntry;
import com.allfolio.infra.cache.CandleCacheProperties;
import com.allfolio.infra.cache.CandleCacheStore;
import com.allfolio.infra.cache.CandleRangeLock;
import com.allfolio.infra.price.StockPriceClient;
import com.allfolio.infra.price.TwelveDataClient;
import com.allfolio.infra.price.UpbitPriceClient;
import com.allfolio.web.dto.CandleSeriesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CandleService 오케스트레이션(docs/ROADMAP.md Task 028) 순수 단위 테스트. 실제 Redis·외부 API는
 * 각각 CandleCacheStore/CandleRangeLock/UpbitPriceClient/StockPriceClient/TwelveDataClient를
 * mock으로 대체한다. {@link com.allfolio.domain.CandleAggregator}·
 * {@link UpbitPriceClient#aggregateYears}·{@link CandleCacheStore#mergeOlder}의 순수 집계·병합
 * 로직 자체는 이미 전용 테스트(CandleAggregatorTest·UpbitCandleAggregationTest·
 * CandleCacheStoreTest)가 검증하므로, 이 클래스는 라우팅·캐시 판정·락 오케스트레이션·예외
 * 변환만 다룬다(책임 분리, PriceServiceTest와 동일한 패턴).
 */
@ExtendWith(MockitoExtension.class)
class CandleServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private UpbitPriceClient upbitPriceClient;

    @Mock
    private StockPriceClient stockPriceClient;

    @Mock
    private TwelveDataClient twelveDataClient;

    @Mock
    private CandleCacheStore candleCacheStore;

    @Mock
    private CandleRangeLock candleRangeLock;

    private CandleCacheProperties candleCacheProperties;

    private CandleService candleService;

    private final UUID userId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        candleCacheProperties = new CandleCacheProperties(Duration.ofHours(12), 10, Duration.ofSeconds(5));
        candleService = new CandleService(assetRepository, upbitPriceClient, stockPriceClient, twelveDataClient,
                candleCacheStore, candleRangeLock, candleCacheProperties);
    }

    @Test
    void assetNotOwnedByUserThrowsAssetNotFound() {
        when(assetRepository.findByIdAndUser_Id(eq(assetId), eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> candleService.getCandles(userId, assetId, "DAY", null))
                .isInstanceOf(AssetNotFoundException.class);
    }

    @Test
    void cashAssetThrowsPriceUnavailable() {
        givenAsset(AssetType.CASH, "KRW", "KRW-CASH");

        assertThatThrownBy(() -> candleService.getCandles(userId, assetId, "DAY", null))
                .isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void blankIntervalThrowsInvalidCandleQuery() {
        givenAsset(AssetType.COIN, "KRW", "BTC");

        assertThatThrownBy(() -> candleService.getCandles(userId, assetId, "", null))
                .isInstanceOf(InvalidCandleQueryException.class);
    }

    @Test
    void unsupportedIntervalThrowsInvalidCandleQuery() {
        givenAsset(AssetType.COIN, "KRW", "BTC");

        assertThatThrownBy(() -> candleService.getCandles(userId, assetId, "MINUTE", null))
                .isInstanceOf(InvalidCandleQueryException.class);
    }

    @Test
    void intervalParsingIsCaseInsensitive() {
        givenAsset(AssetType.COIN, "KRW", "BTC");
        when(upbitPriceClient.getDayCandles("BTC", "KRW", 200)).thenReturn(List.of());

        CandleSeriesResponse response = candleService.getCandles(userId, assetId, "day", null);

        assertThat(response.bars()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // COIN — 캐시 없는 패스스루
    // ---------------------------------------------------------------------

    @Test
    void coinDayNoBeforeCallsUpbitDayCandlesAndMapsBarsWithEightDecimalScale() {
        givenAsset(AssetType.COIN, "KRW", "BTC");
        Instant candleAt = Instant.parse("2026-09-10T00:00:00Z");
        when(upbitPriceClient.getDayCandles("BTC", "KRW", 200)).thenReturn(List.of(
                new Candle(new BigDecimal("100000000"), new BigDecimal("101000000"),
                        new BigDecimal("99000000"), new BigDecimal("100500000"), candleAt)));

        CandleSeriesResponse response = candleService.getCandles(userId, assetId, "DAY", null);

        assertThat(response.bars()).hasSize(1);
        assertThat(response.bars().get(0).bucketStart()).isEqualTo("2026-09-10T00:00:00Z");
        assertThat(response.bars().get(0).open()).isEqualTo("100000000.00000000");
        assertThat(response.bars().get(0).close()).isEqualTo("100500000.00000000");
        assertThat(response.hasMoreHistory()).isFalse();
        verify(upbitPriceClient, never()).getDayCandles(anyString(), anyString(), anyInt(), any(Instant.class));
    }

    @Test
    void coinDayWithBeforeParsesInstantAndPassesToClient() {
        givenAsset(AssetType.COIN, "KRW", "BTC");
        when(upbitPriceClient.getDayCandles(eq("BTC"), eq("KRW"), eq(200), eq(Instant.parse("2026-09-01T00:00:00Z"))))
                .thenReturn(List.of());

        candleService.getCandles(userId, assetId, "DAY", "2026-09-01T00:00:00Z");

        verify(upbitPriceClient).getDayCandles("BTC", "KRW", 200, Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void coinInvalidBeforeThrowsInvalidCandleQuery() {
        givenAsset(AssetType.COIN, "KRW", "BTC");

        assertThatThrownBy(() -> candleService.getCandles(userId, assetId, "DAY", "not-a-date"))
                .isInstanceOf(InvalidCandleQueryException.class);
        verifyNoInteractions(upbitPriceClient);
    }

    @Test
    void coinHasMoreHistoryTrueWhenPageIsFull() {
        givenAsset(AssetType.COIN, "KRW", "BTC");
        when(upbitPriceClient.getDayCandles("BTC", "KRW", 200)).thenReturn(fullPageOfCoinCandles(200));

        CandleSeriesResponse response = candleService.getCandles(userId, assetId, "DAY", null);

        assertThat(response.hasMoreHistory()).isTrue();
    }

    /** 업비트는 년봉 엔드포인트가 없어 월봉을 받아 집계한다 — hasMoreHistory는 월봉(raw) 개수로 판정한다. */
    @Test
    void coinYearIntervalFetchesMonthCandlesAndAggregates() {
        givenAsset(AssetType.COIN, "KRW", "BTC");
        when(upbitPriceClient.getMonthCandles("BTC", "KRW", 200)).thenReturn(List.of(
                new Candle(new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                        new BigDecimal("105"), Instant.parse("2025-01-01T00:00:00Z")),
                new Candle(new BigDecimal("105"), new BigDecimal("130"), new BigDecimal("95"),
                        new BigDecimal("120"), Instant.parse("2025-06-01T00:00:00Z")),
                new Candle(new BigDecimal("120"), new BigDecimal("125"), new BigDecimal("115"),
                        new BigDecimal("118"), Instant.parse("2026-01-01T00:00:00Z"))));

        CandleSeriesResponse response = candleService.getCandles(userId, assetId, "YEAR", null);

        assertThat(response.bars()).hasSize(2);
        assertThat(response.bars().get(0).bucketStart()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(response.bars().get(1).bucketStart()).isEqualTo("2025-01-01T00:00:00Z");
        assertThat(response.bars().get(1).open()).isEqualTo("100.00000000");
        assertThat(response.bars().get(1).close()).isEqualTo("120.00000000");
        verify(upbitPriceClient, never()).getDayCandles(anyString(), anyString(), anyInt());
    }

    @Test
    void coinMinute1RequestCallsUpbitMinuteCandlesWithUnit1() {
        givenAsset(AssetType.COIN, "KRW", "BTC");
        when(upbitPriceClient.getMinuteCandles("BTC", "KRW", 1, 200)).thenReturn(List.of());

        CandleSeriesResponse response = candleService.getCandles(userId, assetId, "minute1", null);

        assertThat(response.bars()).isEmpty();
        verify(upbitPriceClient).getMinuteCandles("BTC", "KRW", 1, 200);
    }

    @Test
    void stockAssetWithMinuteIntervalThrowsInvalidCandleQuery() {
        givenAsset(AssetType.STOCK, "KRW", "005930");

        assertThatThrownBy(() -> candleService.getCandles(userId, assetId, "MINUTE1", null))
                .isInstanceOf(InvalidCandleQueryException.class);
        verifyNoInteractions(candleCacheStore, stockPriceClient, twelveDataClient, candleRangeLock);
    }

    private List<Candle> fullPageOfCoinCandles(int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> new Candle(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        Instant.parse("2026-01-01T00:00:00Z").minusSeconds(i * 86400L)))
                .toList();
    }

    // ---------------------------------------------------------------------
    // STOCK — CandleCacheStore/CandleRangeLock 오케스트레이션
    // ---------------------------------------------------------------------

    @Test
    void stockCacheHitAtCapReturnsCachedEntryWithoutVendorCallAndHasMoreHistoryFalse() {
        givenAsset(AssetType.STOCK, "KRW", "005930");
        LocalDate maxHistoryStart = LocalDate.now().minusYears(10);
        CandleCacheEntry entry = new CandleCacheEntry(
                List.of(new DailyBar(LocalDate.now(), bd("100"), bd("110"), bd("90"), bd("105"))),
                maxHistoryStart, LocalDate.now(), Instant.now());
        when(candleCacheStore.find("candle:STOCK:005930")).thenReturn(Optional.of(entry));

        CandleSeriesResponse response = candleService.getCandles(userId, assetId, "DAY", null);

        assertThat(response.hasMoreHistory()).isFalse();
        assertThat(response.bars()).hasSize(1);
        verifyNoInteractions(stockPriceClient, twelveDataClient, candleRangeLock);
    }

    @Test
    void stockCacheMissAcquiresLockFetchesFromVendorAndSaves() {
        givenAsset(AssetType.STOCK, "KRW", "005930");
        when(candleCacheStore.find("candle:STOCK:005930")).thenReturn(Optional.empty());
        when(candleRangeLock.tryLock("candle:STOCK:005930")).thenReturn(true);
        List<DailyBar> fetched = List.of(new DailyBar(LocalDate.now(), bd("100"), bd("110"), bd("90"), bd("105")));
        when(stockPriceClient.getDailySeries(eq("005930"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(fetched);
        CandleCacheEntry merged = new CandleCacheEntry(fetched, LocalDate.now().minusYears(3), LocalDate.now(), Instant.now());
        when(candleCacheStore.mergeOlder(any(CandleCacheEntry.class), eq(fetched), any(LocalDate.class)))
                .thenReturn(merged);

        CandleSeriesResponse response = candleService.getCandles(userId, assetId, "DAY", null);

        assertThat(response.hasMoreHistory()).isTrue();
        verify(candleCacheStore).save(eq("candle:STOCK:005930"), eq(merged), eq(Duration.ofHours(12)));
        verify(candleRangeLock).unlock("candle:STOCK:005930");
    }

    /**
     * 국내(KRW)는 begin/end 범위 조회를 지원하므로(StockPriceClient) 항상 begin=maxHistoryStart로
     * 요청한다 — 첫 호출부터 캡 전체를 커버하려는 설계(CandleService 클래스 Javadoc).
     */
    @Test
    void stockKrwCacheMissRequestsBeginAtMaxHistoryStart() {
        givenAsset(AssetType.STOCK, "KRW", "005930");
        when(candleCacheStore.find("candle:STOCK:005930")).thenReturn(Optional.empty());
        when(candleRangeLock.tryLock("candle:STOCK:005930")).thenReturn(true);
        when(stockPriceClient.getDailySeries(eq("005930"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(candleCacheStore.mergeOlder(any(CandleCacheEntry.class), any(), any(LocalDate.class)))
                .thenReturn(new CandleCacheEntry(List.of(), LocalDate.now().minusYears(10), LocalDate.now(), Instant.now()));

        candleService.getCandles(userId, assetId, "DAY", null);

        LocalDate expectedBegin = LocalDate.now().minusYears(10);
        verify(stockPriceClient).getDailySeries("005930", expectedBegin, LocalDate.now());
    }

    @Test
    void stockUsdCacheMissRequestsMaxOutputsizeAndNeverCallsStockPriceClient() {
        givenAsset(AssetType.STOCK, "USD", "AAPL");
        when(candleCacheStore.find("candle:STOCK:AAPL:USD")).thenReturn(Optional.empty());
        when(candleRangeLock.tryLock("candle:STOCK:AAPL:USD")).thenReturn(true);
        when(twelveDataClient.getDailySeries("AAPL", 5000)).thenReturn(List.of());
        when(candleCacheStore.mergeOlder(any(CandleCacheEntry.class), any(), any(LocalDate.class)))
                .thenReturn(new CandleCacheEntry(List.of(), LocalDate.now().minusYears(1), LocalDate.now(), Instant.now()));

        candleService.getCandles(userId, assetId, "DAY", null);

        verify(twelveDataClient).getDailySeries("AAPL", 5000);
        verifyNoInteractions(stockPriceClient);
    }

    /**
     * STOCK+USD는 before 확장을 지원하지 않는다(TwelveDataClient가 begin/end 파라미터를 제공하지
     * 않음) — 캡에 도달하지 않은 캐시라도 그대로 반환하고 벤더를 다시 부르지 않는다.
     */
    @Test
    void stockUsdSkipsExtensionEvenWhenCapNotReachedAndBeforeGiven() {
        givenAsset(AssetType.STOCK, "USD", "AAPL");
        LocalDate oldestCovered = LocalDate.now().minusMonths(6); // 캡(10년)에는 한참 못 미침
        CandleCacheEntry entry = new CandleCacheEntry(
                List.of(new DailyBar(LocalDate.now(), bd("100"), bd("110"), bd("90"), bd("105"))),
                oldestCovered, LocalDate.now(), Instant.now());
        when(candleCacheStore.find("candle:STOCK:AAPL:USD")).thenReturn(Optional.of(entry));

        CandleSeriesResponse response = candleService.getCandles(userId, assetId, "DAY",
                LocalDate.now().minusDays(1).toString());

        assertThat(response.hasMoreHistory()).isTrue();
        verifyNoInteractions(twelveDataClient, stockPriceClient, candleRangeLock);
    }

    /** before로 캐시 oldestCovered보다 더 과거를 요청하면(KRW) 락을 잡고 벤더를 다시 호출해 확장한다. */
    @Test
    void stockKrwBeforeOlderThanCachedTriggersExtensionFetch() {
        givenAsset(AssetType.STOCK, "KRW", "005930");
        LocalDate oldestCovered = LocalDate.now().minusMonths(6);
        LocalDate before = oldestCovered; // before == oldestCovered → 아직 그보다 이전 데이터는 없음
        CandleCacheEntry cached = new CandleCacheEntry(
                List.of(new DailyBar(LocalDate.now(), bd("100"), bd("110"), bd("90"), bd("105"))),
                oldestCovered, LocalDate.now(), Instant.now());
        when(candleCacheStore.find("candle:STOCK:005930")).thenReturn(Optional.of(cached));
        when(candleRangeLock.tryLock("candle:STOCK:005930")).thenReturn(true);
        List<DailyBar> olderFetched = List.of(
                new DailyBar(oldestCovered.minusDays(1), bd("90"), bd("95"), bd("85"), bd("92")));
        when(stockPriceClient.getDailySeries(eq("005930"), any(LocalDate.class), eq(oldestCovered.minusDays(1))))
                .thenReturn(olderFetched);
        CandleCacheEntry merged = new CandleCacheEntry(olderFetched, LocalDate.now().minusYears(10),
                LocalDate.now(), Instant.now());
        when(candleCacheStore.mergeOlder(eq(cached), eq(olderFetched), any(LocalDate.class))).thenReturn(merged);

        CandleSeriesResponse response = candleService.getCandles(userId, assetId, "DAY", before.toString());

        assertThat(response.hasMoreHistory()).isFalse();
        verify(candleCacheStore).save(eq("candle:STOCK:005930"), eq(merged), any(Duration.class));
    }

    /**
     * 락 획득에 실패하면(다른 요청이 채우는 중) 짧게 폴링 대기한 뒤에도 채워지지 않으면 fail-open으로
     * 직접 조회하고, 그 결과를 캐시에 저장한다(락 경합이 반복될 때마다 매번 벤더를 새로 호출하지
     * 않도록). rangeLockTtl을 짧게 둬 테스트가 빠르게 끝나도록 한다.
     */
    @Test
    void stockLockAcquireFailsPollsThenFallsBackToDirectFetchAndSaves() {
        CandleCacheProperties shortLockProperties = new CandleCacheProperties(Duration.ofHours(12), 10, Duration.ofMillis(10));
        CandleService serviceWithShortLock = new CandleService(assetRepository, upbitPriceClient, stockPriceClient,
                twelveDataClient, candleCacheStore, candleRangeLock, shortLockProperties);
        givenAsset(AssetType.STOCK, "KRW", "005930");
        when(candleCacheStore.find("candle:STOCK:005930")).thenReturn(Optional.empty());
        when(candleRangeLock.tryLock("candle:STOCK:005930")).thenReturn(false);
        when(stockPriceClient.getDailySeries(eq("005930"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        CandleCacheEntry merged = new CandleCacheEntry(List.of(), LocalDate.now().minusYears(10), LocalDate.now(), Instant.now());
        when(candleCacheStore.mergeOlder(any(CandleCacheEntry.class), any(), any(LocalDate.class))).thenReturn(merged);

        CandleSeriesResponse response = serviceWithShortLock.getCandles(userId, assetId, "DAY", null);

        assertThat(response.hasMoreHistory()).isFalse();
        verify(stockPriceClient, times(1)).getDailySeries(eq("005930"), any(LocalDate.class), any(LocalDate.class));
        verify(candleCacheStore).save(eq("candle:STOCK:005930"), eq(merged), any(Duration.class));
        verify(candleRangeLock, never()).unlock(anyString());
    }

    @Test
    void invalidBeforeForStockThrowsInvalidCandleQuery() {
        givenAsset(AssetType.STOCK, "KRW", "005930");

        assertThatThrownBy(() -> candleService.getCandles(userId, assetId, "DAY", "09/01/2026"))
                .isInstanceOf(InvalidCandleQueryException.class);
        verifyNoInteractions(candleCacheStore, stockPriceClient, twelveDataClient, candleRangeLock);
    }

    @Test
    void stockBeforeFiltersOutBarsOnOrAfterBoundary() {
        givenAsset(AssetType.STOCK, "KRW", "005930");
        LocalDate day1 = LocalDate.of(2026, 9, 1);
        LocalDate day2 = LocalDate.of(2026, 9, 2);
        LocalDate day3 = LocalDate.of(2026, 9, 3);
        CandleCacheEntry entry = new CandleCacheEntry(
                List.of(
                        new DailyBar(day1, bd("100"), bd("110"), bd("90"), bd("105")),
                        new DailyBar(day2, bd("105"), bd("115"), bd("95"), bd("110")),
                        new DailyBar(day3, bd("110"), bd("120"), bd("100"), bd("115"))),
                LocalDate.now().minusYears(10), LocalDate.now(), Instant.now());
        when(candleCacheStore.find("candle:STOCK:005930")).thenReturn(Optional.of(entry));

        CandleSeriesResponse response = candleService.getCandles(userId, assetId, "DAY", day3.toString());

        assertThat(response.bars()).hasSize(2);
        assertThat(response.bars().get(0).bucketStart()).isEqualTo(day2.toString());
        assertThat(response.bars().get(1).bucketStart()).isEqualTo(day1.toString());
    }

    private void givenAsset(AssetType assetType, String currency, String ticker) {
        User user = User.of("trader@example.com", "hash");
        Asset asset = Asset.of(user, ticker, "테스트 자산", assetType, currency);
        when(assetRepository.findByIdAndUser_Id(eq(assetId), eq(userId))).thenReturn(Optional.of(asset));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
