package com.allfolio;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Holding;
import com.allfolio.domain.Price;
import com.allfolio.domain.PricedQuote;
import com.allfolio.domain.User;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.domain.repository.HoldingRepository;
import com.allfolio.domain.service.PortfolioService;
import com.allfolio.domain.service.PriceService;
import com.allfolio.web.dto.PortfolioItemResponse;
import com.allfolio.web.dto.PortfolioResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * PortfolioService.listPortfolio()의 evaluationKrw/unrealizedPnl/weight 계산(Task 023) 순수 단위
 * 테스트. AssetRepository/HoldingRepository/PriceService를 모두 mock으로 대체해 Spring 컨텍스트
 * (Testcontainers·Redis·외부 API)를 띄우지 않는다 — PriceServiceTest/SimulationServiceTest와 동일한
 * 책임 분리 컨벤션. WireMock을 갖춘 부분 실패·Throttle 통합 테스트는 다음 서브태스크 몫이라 여기서는
 * 최소한의 계산 정확성·안전성만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private PriceService priceService;

    /** TransactionTemplate이 요구하는 최소한의 PlatformTransactionManager — 커밋/롤백을 실제로 수행할
     * 리소스가 없는 순수 단위 테스트이므로 아무 부작용 없이 트랜잭션 상태만 흉내낸다. */
    private static final PlatformTransactionManager NOOP_TRANSACTION_MANAGER = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
        }
    };

    private PortfolioService portfolioService;

    private final UUID userId = UUID.randomUUID();
    private final User user = User.of("trader@example.com", "hash");

    @BeforeEach
    void setUp() {
        portfolioService = new PortfolioService(assetRepository, holdingRepository, priceService,
                NOOP_TRANSACTION_MANAGER);
    }

    /**
     * STOCK/COIN/CASH(KRW)/CASH(USD)가 섞인 포트폴리오. 삼성전자(STOCK)는 현재가 70000원 ×10주=700000,
     * 원가 600000 → 손익 +100000. BTC(COIN)는 현재가 90000000원×0.1=9000000, 원가 8000000 → 손익
     * +1000000. KRW 현금 500000은 그대로 평가금액=원가, 손익 0. USD 현금 100(원가 1 고정)은 환율
     * 1350원 적용 시 evaluationKrw=135000, costKrw=100×1350=135000 → 손익 0.
     * total = 700000+9000000+500000+135000 = 10335000, 삼성전자 weight = 700000/10335000*100 ≈ 6.77.
     */
    @Test
    void computesEvaluationPnlAndWeightAcrossMixedAssetTypes() {
        Asset stock = asset(AssetType.STOCK, "005930", "삼성전자", "KRW");
        Asset coin = asset(AssetType.COIN, "BTC", "비트코인", "KRW");
        Asset cashKrw = asset(AssetType.CASH, "KRW", "원화 예수금", "KRW");
        Asset cashUsd = asset(AssetType.CASH, "USD-CASH", "달러 예수금", "USD");

        given(userId, List.of(stock, coin, cashKrw, cashUsd), Map.of(
                stock, holding(stock, "10", "60000"),
                coin, holding(coin, "0.1", "80000000"),
                cashKrw, holding(cashKrw, "500000", "1"),
                cashUsd, holding(cashUsd, "100", "1")));

        when(priceService.quoteForPortfolio(stock)).thenReturn(quote("70000"));
        when(priceService.quoteForPortfolio(coin)).thenReturn(quote("90000000.00000000"));
        when(priceService.quoteForPortfolio(cashUsd)).thenReturn(quote("1350"));

        PortfolioResponse response = portfolioService.listPortfolio(userId);

        PortfolioItemResponse stockItem = itemOf(response, "005930");
        assertThat(new BigDecimal(stockItem.evaluationKrw())).isEqualByComparingTo("700000");
        assertThat(new BigDecimal(stockItem.unrealizedPnl())).isEqualByComparingTo("100000");

        PortfolioItemResponse coinItem = itemOf(response, "BTC");
        assertThat(new BigDecimal(coinItem.evaluationKrw())).isEqualByComparingTo("9000000");
        assertThat(new BigDecimal(coinItem.unrealizedPnl())).isEqualByComparingTo("1000000");
        // COIN은 draft.cost()가 스케일 8(PrecisionScale.COIN)이라, subtract() 결과 스케일이 evaluationKrw
        // (스케일 0)가 아닌 8을 따라갈 위험이 있다("1000000.00000000") — compareTo만으로는 이 스케일
        // 버그를 못 잡으므로 정확한 문자열로 KRW 정수 스케일(0)임을 확정한다(Task 023 Major 1 회귀 방지).
        assertThat(coinItem.unrealizedPnl()).isEqualTo("1000000");

        PortfolioItemResponse cashKrwItem = itemOf(response, "KRW");
        assertThat(new BigDecimal(cashKrwItem.evaluationKrw())).isEqualByComparingTo("500000");
        assertThat(new BigDecimal(cashKrwItem.unrealizedPnl())).isEqualByComparingTo("0");

        PortfolioItemResponse cashUsdItem = itemOf(response, "USD-CASH");
        assertThat(new BigDecimal(cashUsdItem.evaluationKrw())).isEqualByComparingTo("135000");
        assertThat(new BigDecimal(cashUsdItem.unrealizedPnl())).isEqualByComparingTo("0");

        assertThat(new BigDecimal(response.totalEvaluationKrw())).isEqualByComparingTo("10335000");
        assertThat(new BigDecimal(response.totalUnrealizedPnl())).isEqualByComparingTo("1100000");

        // 700000 / 10335000 * 100 = 6.7729... → HALF_UP scale 2 → 6.77
        assertThat(new BigDecimal(stockItem.weight())).isEqualByComparingTo("6.77");
    }

    /** 자산이 하나뿐이면 evaluationKrw가 곧 totalEvaluationKrw와 같아 weight는 정확히 100.00이어야 한다. */
    @Test
    void singleAssetPortfolioHasExactly100PercentWeight() {
        Asset stock = asset(AssetType.STOCK, "005930", "삼성전자", "KRW");
        given(userId, List.of(stock), Map.of(stock, holding(stock, "10", "60000")));
        when(priceService.quoteForPortfolio(stock)).thenReturn(quote("70000"));

        PortfolioResponse response = portfolioService.listPortfolio(userId);

        PortfolioItemResponse item = itemOf(response, "005930");
        assertThat(new BigDecimal(item.weight())).isEqualByComparingTo("100.00");
    }

    /**
     * 모든 자산의 시세 조회가 실패하면(Optional.empty) evaluationKrw/unrealizedPnl/weight와 totalEvaluationKrw/
     * totalUnrealizedPnl이 전부 null이어야 한다 — 예외 없이 안전하게 처리되어야 한다(사용자 확정 정책).
     */
    @Test
    void allPriceQuoteFailuresLeaveTotalsAndWeightsNullWithoutThrowing() {
        Asset stock = asset(AssetType.STOCK, "005930", "삼성전자", "KRW");
        Asset coin = asset(AssetType.COIN, "BTC", "비트코인", "KRW");
        given(userId, List.of(stock, coin), Map.of(
                stock, holding(stock, "10", "60000"),
                coin, holding(coin, "0.1", "80000000")));
        when(priceService.quoteForPortfolio(stock)).thenReturn(Optional.empty());
        when(priceService.quoteForPortfolio(coin)).thenReturn(Optional.empty());

        PortfolioResponse response = portfolioService.listPortfolio(userId);

        assertThat(response.totalEvaluationKrw()).isNull();
        assertThat(response.totalUnrealizedPnl()).isNull();
        for (PortfolioItemResponse item : response.items()) {
            assertThat(item.evaluationKrw()).isNull();
            assertThat(item.unrealizedPnl()).isNull();
            assertThat(item.weight()).isNull();
        }
    }

    private void given(UUID userId, List<Asset> assets, Map<Asset, Holding> holdingsByAsset) {
        when(assetRepository.findByUser_IdOrderByIdDesc(userId)).thenReturn(assets);
        when(holdingRepository.findByAsset_IdIn(any())).thenReturn(List.copyOf(holdingsByAsset.values()));
    }

    private Asset asset(AssetType assetType, String ticker, String name, String currency) {
        Asset asset = Asset.of(user, ticker, name, assetType, currency);
        setId(asset, UUID.randomUUID());
        return asset;
    }

    /** Asset.id는 @GeneratedValue라 영속화 없이는 null이다 — mock 시나리오 전용으로 리플렉션으로 채운다. */
    private void setId(Asset asset, UUID id) {
        try {
            var field = Asset.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(asset, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Holding holding(Asset asset, String quantity, String avgPrice) {
        return Holding.of(asset, new BigDecimal(quantity), new BigDecimal(avgPrice));
    }

    private Optional<PricedQuote> quote(String krwAmount) {
        return Optional.of(new PricedQuote(new Price(new BigDecimal(krwAmount), "KRW", Instant.now()), false));
    }

    private PortfolioItemResponse itemOf(PortfolioResponse response, String ticker) {
        return response.items().stream()
                .filter(item -> ticker.equals(item.ticker()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("ticker " + ticker + " not found"));
    }
}
