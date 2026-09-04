package com.allfolio;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Price;
import com.allfolio.domain.PricedQuote;
import com.allfolio.domain.User;
import com.allfolio.domain.exception.AssetNotFoundException;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.PriceRateLimitExceededException;
import com.allfolio.domain.exception.PriceUnavailableException;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.domain.service.PriceService;
import com.allfolio.infra.cache.PriceCacheProperties;
import com.allfolio.infra.cache.PriceCacheStore;
import com.allfolio.infra.cache.PriceThrottle;
import com.allfolio.infra.price.ExchangeRateClient;
import com.allfolio.infra.price.StockPriceClient;
import com.allfolio.infra.price.UpbitPriceClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PriceService.getPrice()의 자산 유형별 라우팅과 캐시·Throttle 오케스트레이션(Task 022)을 검증하는
 * 순수 단위 테스트. 실제 외부 API 호출·Redis는 각각 클라이언트/PriceCacheStore/PriceThrottle을 mock으로
 * 대체한다 — Spring 컨텍스트 없이 라우팅·캐시 판정·예외·스케일 정규화만 다룬다(책임 분리).
 */
@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private UpbitPriceClient upbitPriceClient;

    @Mock
    private StockPriceClient stockPriceClient;

    @Mock
    private ExchangeRateClient exchangeRateClient;

    @Mock
    private PriceCacheStore priceCacheStore;

    @Mock
    private PriceThrottle priceThrottle;

    private final PriceCacheProperties priceCacheProperties = new PriceCacheProperties(
            Duration.ofSeconds(10), Duration.ofHours(12), Duration.ofHours(12), Duration.ofHours(24),
            Duration.ofSeconds(30));

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private PriceService priceService;

    private final UUID userId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        priceService = new PriceService(assetRepository, upbitPriceClient, stockPriceClient, exchangeRateClient,
                priceCacheStore, priceThrottle, priceCacheProperties, meterRegistry);
        // 대다수 테스트는 캐시 미스 + Throttle 허용을 전제로 라우팅/스케일 로직만 검증한다.
        // 캐시 히트를 다루는 테스트가 eq() 매칭 stub으로 이 기본값을 덮어쓴다.
        lenient().when(priceCacheStore.find(anyString(), any(Duration.class))).thenReturn(Optional.empty());
        lenient().when(priceThrottle.tryAcquire(any(UUID.class))).thenReturn(true);
    }

    @Test
    void coinAssetRoutesToUpbitAndScalesToEightDecimals() {
        givenAsset(AssetType.COIN, "KRW");
        when(upbitPriceClient.getPrice("BTC"))
                .thenReturn(new Price(new BigDecimal("123456789.1"), "KRW", Instant.now()));

        PricedQuote quote = priceService.getPrice(userId, assetId);

        assertThat(quote.stale()).isFalse();
        assertThat(quote.price().amount()).isEqualByComparingTo("123456789.10000000");
        assertThat(quote.price().amount().scale()).isEqualTo(8);
        verify(priceCacheStore).save(eq("price:COIN:BTC"), any(Price.class));
    }

    @Test
    void cashUsdAssetRoutesToExchangeRateAndScalesByResponseCurrencyNotAssetCurrency() {
        givenAsset(AssetType.CASH, "USD");
        when(exchangeRateClient.getUsdKrwRate())
                .thenReturn(new Price(new BigDecimal("1350.5"), "KRW", Instant.now()));

        PricedQuote quote = priceService.getPrice(userId, assetId);

        // asset.getCurrency()는 "USD"지만 실제 응답 통화는 "KRW"다 — 스케일은 응답 통화(KRW, 0자리) 기준이어야
        // "amount":"1351","currency":"KRW"처럼 currency와 scale이 서로 다른 기준을 쓰는 자기모순을 피한다.
        assertThat(quote.price().amount()).isEqualByComparingTo("1351");
        assertThat(quote.price().amount().scale()).isEqualTo(0);
        assertThat(quote.price().currency()).isEqualTo("KRW");
        verify(priceCacheStore).save(eq("price:CASH:USD"), any(Price.class));
    }

    @Test
    void cashKrwAssetThrowsPriceUnavailable() {
        givenAsset(AssetType.CASH, "KRW");

        assertThatThrownBy(() -> priceService.getPrice(userId, assetId))
                .isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void stockAssetRoutesToStockPriceClientAndScalesToZeroDecimals() {
        givenAsset(AssetType.STOCK, "KRW");
        when(stockPriceClient.getPrice("BTC"))
                .thenReturn(new Price(new BigDecimal("71000.00"), "KRW", Instant.now()));

        PricedQuote quote = priceService.getPrice(userId, assetId);

        assertThat(quote.price().amount()).isEqualByComparingTo("71000");
        assertThat(quote.price().amount().scale()).isEqualTo(0);
    }

    @Test
    void assetNotOwnedByUserThrowsAssetNotFound() {
        when(assetRepository.findByIdAndUser_Id(eq(assetId), eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> priceService.getPrice(userId, assetId))
                .isInstanceOf(AssetNotFoundException.class);
    }

    /**
     * Timer.Sample이 예외 경로(404)에서도 stop되어야 allfolio.price.fetch.duration이 실패 케이스를
     * 놓치지 않는다 (code-reviewer 지적, Task 021 후속). 캐시 히트가 아니므로 source=external 태그로 찍힌다.
     */
    @Test
    void assetNotFoundStillRecordsFetchDurationMetric() {
        when(assetRepository.findByIdAndUser_Id(eq(assetId), eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> priceService.getPrice(userId, assetId))
                .isInstanceOf(AssetNotFoundException.class);

        assertThat(meterRegistry.timer("allfolio.price.fetch.duration", "source", "external").count()).isEqualTo(1);
    }

    /** 캐시도 없고 외부 API도 실패하면 폴백 대상이 없으므로 기존과 동일하게 503 경로로 전파된다. */
    @Test
    void externalApiFailureStillRecordsFetchDurationMetric() {
        givenAsset(AssetType.COIN, "KRW");
        when(upbitPriceClient.getPrice("BTC"))
                .thenThrow(new ExternalPriceApiException("업비트 조회 실패"));

        assertThatThrownBy(() -> priceService.getPrice(userId, assetId))
                .isInstanceOf(ExternalPriceApiException.class);

        assertThat(meterRegistry.timer("allfolio.price.fetch.duration", "source", "external").count()).isEqualTo(1);
    }

    /** 캐시가 신선하면 외부 API·Throttle 모두 건드리지 않고 즉시 반환한다(Task 022 확정 정책). */
    @Test
    void cacheHitReturnsFreshQuoteWithoutCallingExternalClientOrThrottle() {
        givenAsset(AssetType.COIN, "KRW");
        Price cachedPrice = new Price(new BigDecimal("100000000.00000000"), "KRW", Instant.now());
        when(priceCacheStore.find(eq("price:COIN:BTC"), any(Duration.class)))
                .thenReturn(Optional.of(new PricedQuote(cachedPrice, false)));

        PricedQuote quote = priceService.getPrice(userId, assetId);

        assertThat(quote.stale()).isFalse();
        // Price는 내부에 BigDecimal을 가진 레코드라 isEqualTo()가 결국 BigDecimal.equals()를 타
        // scale 차이에 취약하다(.claude/rules/testing.md) — amount는 compareTo, 나머지는 개별 비교.
        assertThat(quote.price().amount()).isEqualByComparingTo(cachedPrice.amount());
        assertThat(quote.price().currency()).isEqualTo(cachedPrice.currency());
        assertThat(quote.price().asOf()).isEqualTo(cachedPrice.asOf());
        verifyNoInteractions(upbitPriceClient, stockPriceClient, exchangeRateClient, priceThrottle);
        verify(priceCacheStore, never()).save(anyString(), any(Price.class));
        assertThat(meterRegistry.timer("allfolio.price.fetch.duration", "source", "cache").count()).isEqualTo(1);
    }

    /** 캐시가 신선하지 않아도 값이 남아있으면, 외부 API 장애 시 그 값을 stale=true로 폴백한다. */
    @Test
    void staleFallbackReturnedWhenExternalApiFailsButStaleCacheExists() {
        givenAsset(AssetType.COIN, "KRW");
        Price stalePrice = new Price(new BigDecimal("90000000.00000000"), "KRW", Instant.now());
        when(priceCacheStore.find(eq("price:COIN:BTC"), any(Duration.class)))
                .thenReturn(Optional.of(new PricedQuote(stalePrice, true)));
        when(upbitPriceClient.getPrice("BTC")).thenThrow(new ExternalPriceApiException("업비트 조회 실패"));

        PricedQuote quote = priceService.getPrice(userId, assetId);

        assertThat(quote.stale()).isTrue();
        assertThat(quote.price().amount()).isEqualByComparingTo(stalePrice.amount());
        assertThat(quote.price().currency()).isEqualTo(stalePrice.currency());
        assertThat(quote.price().asOf()).isEqualTo(stalePrice.asOf());
        verify(priceThrottle).tryAcquire(userId);
        verify(priceCacheStore, never()).save(anyString(), any(Price.class));
    }

    /** 캐시 미스/스테일 상태에서 Throttle 한도를 넘기면 외부 API를 시도조차 하지 않는다. */
    @Test
    void throttleExceededThrowsPriceRateLimitExceededAndRecordsMetric() {
        givenAsset(AssetType.COIN, "KRW");
        when(priceThrottle.tryAcquire(userId)).thenReturn(false);

        assertThatThrownBy(() -> priceService.getPrice(userId, assetId))
                .isInstanceOf(PriceRateLimitExceededException.class);

        verifyNoInteractions(upbitPriceClient, stockPriceClient, exchangeRateClient);
        assertThat(meterRegistry.timer("allfolio.price.fetch.duration", "source", "external").count()).isEqualTo(1);
    }

    private void givenAsset(AssetType assetType, String currency) {
        User user = User.of("trader@example.com", "hash");
        Asset asset = Asset.of(user, "BTC", "비트코인", assetType, currency);

        when(assetRepository.findByIdAndUser_Id(eq(assetId), eq(userId))).thenReturn(Optional.of(asset));
    }

    /**
     * quoteForPortfolio()는 GET /v1/portfolio(Task 023) 전용 경로 — 소유권 검증은 호출자 책임이라
     * assetRepository를 거치지 않고, 이미 확보한 Asset 엔티티를 직접 받는다.
     */
    private Asset newAsset(AssetType assetType, String currency) {
        User user = User.of("trader@example.com", "hash");
        return Asset.of(user, "BTC", "비트코인", assetType, currency);
    }

    @Test
    void quoteForPortfolioReturnsEmptyForCashKrwWithoutCallingAnyExternalClient() {
        Asset cashKrw = newAsset(AssetType.CASH, "KRW");

        Optional<PricedQuote> quote = priceService.quoteForPortfolio(cashKrw);

        assertThat(quote).isEmpty();
        verifyNoInteractions(upbitPriceClient, stockPriceClient, exchangeRateClient, priceCacheStore, priceThrottle);
    }

    @Test
    void quoteForPortfolioReturnsEmptyWhenExternalApiFailsInsteadOfThrowing() {
        Asset coin = newAsset(AssetType.COIN, "KRW");
        when(upbitPriceClient.getPrice("BTC")).thenThrow(new ExternalPriceApiException("업비트 조회 실패"));

        Optional<PricedQuote> quote = priceService.quoteForPortfolio(coin);

        assertThat(quote).isEmpty();
    }

    @Test
    void quoteForPortfolioNeverConsumesThrottleAcrossRepeatedCalls() {
        Asset coin = newAsset(AssetType.COIN, "KRW");
        when(upbitPriceClient.getPrice("BTC"))
                .thenReturn(new Price(new BigDecimal("123456789.1"), "KRW", Instant.now()));

        priceService.quoteForPortfolio(coin);
        priceService.quoteForPortfolio(coin);
        priceService.quoteForPortfolio(coin);

        verify(priceThrottle, never()).tryAcquire(any());
    }

    /**
     * 실패한 조회는 부정 캐싱된다(Task 023 Major 2) — 같은 자산을 반복 조회해도 첫 호출만 외부
     * 클라이언트를 부르고, 그 이후는(부정 캐시 TTL 이내) 외부 호출 없이 즉시 empty를 반환해야 한다.
     */
    @Test
    void quoteForPortfolioMarksFailureAndSkipsExternalCallOnSubsequentCalls() {
        Asset coin = newAsset(AssetType.COIN, "KRW");
        when(upbitPriceClient.getPrice("BTC")).thenThrow(new ExternalPriceApiException("업비트 조회 실패"));
        // 실제 Redis 없이 마커 저장/조회를 흉내낸다 — markFailed() 호출 이후부터 hasRecentFailure()가 true.
        when(priceCacheStore.hasRecentFailure("price:COIN:BTC")).thenReturn(false, true, true);

        Optional<PricedQuote> first = priceService.quoteForPortfolio(coin);
        Optional<PricedQuote> second = priceService.quoteForPortfolio(coin);
        Optional<PricedQuote> third = priceService.quoteForPortfolio(coin);

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        assertThat(third).isEmpty();
        verify(upbitPriceClient, times(1)).getPrice("BTC");
        verify(priceCacheStore).markFailed("price:COIN:BTC");
    }

    /** 부정 캐시 TTL이 지나면(hasRecentFailure가 다시 false로 바뀌면) 재시도한다. */
    @Test
    void quoteForPortfolioRetriesExternalCallAfterNegativeCacheExpires() {
        Asset coin = newAsset(AssetType.COIN, "KRW");
        when(upbitPriceClient.getPrice("BTC"))
                .thenThrow(new ExternalPriceApiException("업비트 조회 실패"))
                .thenReturn(new Price(new BigDecimal("123456789.1"), "KRW", Instant.now()));
        // 첫 호출: 실패 이력 없음 → 외부 호출 시도. 두 번째 호출: TTL 만료로 다시 실패 이력 없음 → 재시도.
        when(priceCacheStore.hasRecentFailure("price:COIN:BTC")).thenReturn(false, false);

        Optional<PricedQuote> first = priceService.quoteForPortfolio(coin);
        Optional<PricedQuote> second = priceService.quoteForPortfolio(coin);

        assertThat(first).isEmpty();
        assertThat(second).isPresent();
        verify(upbitPriceClient, times(2)).getPrice("BTC");
    }
}
