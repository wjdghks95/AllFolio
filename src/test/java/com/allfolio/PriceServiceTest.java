package com.allfolio;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Price;
import com.allfolio.domain.User;
import com.allfolio.domain.exception.AssetNotFoundException;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.PriceUnavailableException;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.domain.service.PriceService;
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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * PriceService.getPrice()의 자산 유형별 라우팅만 검증하는 순수 단위 테스트
 * (docs/ROADMAP.md Task 021). 실제 외부 API 호출은 UpbitPriceClient/StockPriceClient/ExchangeRateClient를
 * mock으로 대체한다 — Spring 컨텍스트 없이 라우팅·예외·스케일 정규화만 다룬다(책임 분리).
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

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private PriceService priceService;

    private final UUID userId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        priceService = new PriceService(assetRepository, upbitPriceClient, stockPriceClient, exchangeRateClient,
                meterRegistry);
    }

    @Test
    void coinAssetRoutesToUpbitAndScalesToEightDecimals() {
        givenAsset(AssetType.COIN, "KRW");
        when(upbitPriceClient.getPrice("BTC"))
                .thenReturn(new Price(new BigDecimal("123456789.1"), "KRW", Instant.now()));

        Price price = priceService.getPrice(userId, assetId);

        assertThat(price.amount()).isEqualByComparingTo("123456789.10000000");
        assertThat(price.amount().scale()).isEqualTo(8);
    }

    @Test
    void cashUsdAssetRoutesToExchangeRateAndScalesByResponseCurrencyNotAssetCurrency() {
        givenAsset(AssetType.CASH, "USD");
        when(exchangeRateClient.getUsdKrwRate())
                .thenReturn(new Price(new BigDecimal("1350.5"), "KRW", Instant.now()));

        Price price = priceService.getPrice(userId, assetId);

        // asset.getCurrency()는 "USD"지만 실제 응답 통화는 "KRW"다 — 스케일은 응답 통화(KRW, 0자리) 기준이어야
        // "amount":"1351","currency":"KRW"처럼 currency와 scale이 서로 다른 기준을 쓰는 자기모순을 피한다.
        assertThat(price.amount()).isEqualByComparingTo("1351");
        assertThat(price.amount().scale()).isEqualTo(0);
        assertThat(price.currency()).isEqualTo("KRW");
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

        Price price = priceService.getPrice(userId, assetId);

        assertThat(price.amount()).isEqualByComparingTo("71000");
        assertThat(price.amount().scale()).isEqualTo(0);
    }

    @Test
    void assetNotOwnedByUserThrowsAssetNotFound() {
        when(assetRepository.findByIdAndUser_Id(eq(assetId), eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> priceService.getPrice(userId, assetId))
                .isInstanceOf(AssetNotFoundException.class);
    }

    /**
     * Timer.Sample이 예외 경로(404)에서도 stop되어야 allfolio.price.fetch.duration이 실패 케이스를
     * 놓치지 않는다 (code-reviewer 지적, Task 021 후속).
     */
    @Test
    void assetNotFoundStillRecordsFetchDurationMetric() {
        when(assetRepository.findByIdAndUser_Id(eq(assetId), eq(userId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> priceService.getPrice(userId, assetId))
                .isInstanceOf(AssetNotFoundException.class);

        assertThat(meterRegistry.timer("allfolio.price.fetch.duration").count()).isEqualTo(1);
    }

    /** 외부 API 실패(503)로 이어지는 경로도 마찬가지로 메트릭에서 누락되면 안 된다. */
    @Test
    void externalApiFailureStillRecordsFetchDurationMetric() {
        givenAsset(AssetType.COIN, "KRW");
        when(upbitPriceClient.getPrice("BTC"))
                .thenThrow(new ExternalPriceApiException("업비트 조회 실패"));

        assertThatThrownBy(() -> priceService.getPrice(userId, assetId))
                .isInstanceOf(ExternalPriceApiException.class);

        assertThat(meterRegistry.timer("allfolio.price.fetch.duration").count()).isEqualTo(1);
    }

    private void givenAsset(AssetType assetType, String currency) {
        User user = User.of("trader@example.com", "hash");
        Asset asset = Asset.of(user, "BTC", "비트코인", assetType, currency);

        when(assetRepository.findByIdAndUser_Id(eq(assetId), eq(userId))).thenReturn(Optional.of(asset));
    }
}
