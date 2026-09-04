package com.allfolio;

import com.allfolio.domain.repository.UserRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.ObjectMapper;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 013·023 — GET /v1/portfolio 포트폴리오 홈 API 통합 검증.
 * AssetIntegrationTest의 MockMvcTester 컨벤션(post/authorizedXxx 헬퍼, bodyOf)을
 * 그대로 따르되, 저장소 컨벤션(각 통합 테스트가 헬퍼를 독립 보유)에 맞춰 복붙한다.
 *
 * <p>Task 023(평가금액·비중·손익)부터는 AssetPriceIntegrationTest와 동일하게 WireMock 3개(업비트·
 * 환율·주식)를 띄운다 — dynamicPort가 클래스마다 달라 AssetPriceIntegrationTest와는 별도 Spring
 * 컨텍스트가 되는 것은 불가피하다(Task 022가 이미 정리한 것과 같은 종류의 트레이드오프). 다만 이 클래스
 * 안에서 프로퍼티가 다른 여러 컨텍스트로 더 쪼개지지 않도록 이 파일 전체가 WireMock 설정 하나를 공유한다.
 */
@AutoConfigureMockMvc
class PortfolioIntegrationTest extends AbstractIntegrationTest {

    private static WireMockServer upbitWireMock;
    private static WireMockServer exchangeRateWireMock;
    private static WireMockServer stockWireMock;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private String tokenA;
    private String tokenB;

    @BeforeAll
    static void startWireMock() {
        upbitWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upbitWireMock.start();
        exchangeRateWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        exchangeRateWireMock.start();
        stockWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        stockWireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        upbitWireMock.stop();
        exchangeRateWireMock.stop();
        stockWireMock.stop();
    }

    @DynamicPropertySource
    static void priceProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.upbit.base-url", () -> "http://localhost:" + upbitWireMock.port());
        registry.add("allfolio.exchange-rate.base-url", () -> "http://localhost:" + exchangeRateWireMock.port());
        registry.add("allfolio.stock.base-url", () -> "http://localhost:" + stockWireMock.port());
    }

    /**
     * resilience4j CircuitBreaker(upbit/exchange-rate/stock)는 이 클래스가 공유하는 단일 Spring
     * 컨텍스트 안에서 빈 하나로 유지된다 — WireMock 스텁 없이 시세 조회 실패를 의도적으로 재현하는
     * 테스트들(예: portfolioItemAndTopLevelValuationFieldsArePresentButNull)이 CB의 실패 카운트를
     * 누적시켜(slidingWindowSize=4) 뒤이어 실행되는, 정상 스텁을 갖춘 테스트까지 CB OPEN 상태로 인해
     * WireMock을 아예 호출하지 못하고 실패하는 것을 실측했다 — 매 테스트 시작 전 강제로 CLOSED로
     * 리셋해 테스트 간 상태 누수를 차단한다.
     */
    @BeforeEach
    void setUp() {
        upbitWireMock.resetAll();
        exchangeRateWireMock.resetAll();
        stockWireMock.resetAll();
        circuitBreakerRegistry.circuitBreaker("upbit").reset();
        circuitBreakerRegistry.circuitBreaker("exchange-rate").reset();
        circuitBreakerRegistry.circuitBreaker("stock").reset();
        userRepository.deleteAll();
        tokenA = accessTokenOf(signup("trader-a@example.com", "correct-horse-battery"));
        tokenB = accessTokenOf(signup("trader-b@example.com", "correct-horse-battery"));
    }

    @Test
    void portfolioWithNoAssetsReturnsEmptyItemsAndEmptyTotals() {
        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        assertThat(itemsOf(body)).isEmpty();
        assertThat(totalCostByCurrencyOf(body)).isEmpty();
    }

    @Test
    void portfolioAggregatesCostAcrossAssetTypesAndCurrencies() {
        createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000"));
        createAsset(tokenA, stockRequest("005930", "삼성전자", "3", "58500"));
        createAsset(tokenA, coinRequest("BTC", "비트코인", "0.05123456", "82000000"));
        createAsset(tokenA, cashRequest("KRW", "원화 예수금", "1500000", "1"));
        createAsset(tokenA, stockRequestWithCurrency("AAPL", "Apple Inc.", "12", "182.5000", "USD"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        List<Map<String, Object>> items = itemsOf(body);
        assertThat(items).hasSize(5);

        // quantity는 자산유형·통화 무관 항상 8자리, avgPrice는 cost와 동일한 스케일 규칙(COIN 8자리,
        // 그 외 통화 기준)을 따른다 — code-reviewer 2차 검증이 이 필드들의 검증 공백을 지적해 추가.
        Map<String, Object> samsung1 = itemByTickerAndCost(items, "005930", "600000");
        assertThat(samsung1.get("cost")).isEqualTo("600000");
        assertThat(samsung1.get("quantity")).isEqualTo("10.00000000");
        assertThat(samsung1.get("avgPrice")).isEqualTo("60000");
        Map<String, Object> samsung2 = itemByTickerAndCost(items, "005930", "175500");
        assertThat(samsung2.get("cost")).isEqualTo("175500");
        Map<String, Object> btc = itemByTicker(items, "BTC");
        assertThat(btc.get("cost")).isEqualTo("4201233.92000000");
        assertThat(btc.get("quantity")).isEqualTo("0.05123456");
        assertThat(btc.get("avgPrice")).isEqualTo("82000000.00000000");
        Map<String, Object> cash = itemByTicker(items, "KRW");
        assertThat(cash.get("cost")).isEqualTo("1500000");
        assertThat(cash.get("quantity")).isEqualTo("1500000.00000000");
        assertThat(cash.get("avgPrice")).isEqualTo("1");
        Map<String, Object> aapl = itemByTicker(items, "AAPL");
        assertThat(aapl.get("cost")).isEqualTo("2190.0000");
        assertThat(aapl.get("quantity")).isEqualTo("12.00000000");
        assertThat(aapl.get("avgPrice")).isEqualTo("182.5000");

        // 통화별 합계는 스케일이 큰 COIN 원가(4201233.92000000)가 섞여도 통화 스케일(KRW 0자리)로
        // 반올림된다 — 600000+175500+4201233.92+1500000=6476733.92 → HALF_UP, scale 0 → 6476734.
        Map<String, String> totalCostByCurrency = totalCostByCurrencyOf(body);
        assertThat(totalCostByCurrency.get("KRW")).isEqualTo("6476734");
        assertThat(totalCostByCurrency.get("USD")).isEqualTo("2190.0000");
    }

    /**
     * cost는 반올림 전 원본 정밀도(quantity × avgPrice 원본값)로 계산 후 스케일을 적용하고,
     * avgPrice는 응답에 나가기 직전 별도로 반올림된다 — 그래서 quantity × 응답의 avgPrice로
     * 재계산하면 반올림 오차만큼 어긋날 수 있다(docs/ROADMAP.md 「GET /v1/portfolio 응답 예시」
     * 절 「주의」에 명문화된 의도된 동작, code-reviewer 2차 검증에서 발견). 60000.75 × 10 =
     * 600007.5 → HALF_UP scale 0 → 600008(cost)이지만, avgPrice 자체는 60000.75 → 60001로
     * 반올림돼 응답에 나간다(10 × 60001 = 600010 ≠ 600008).
     */
    @Test
    void portfolioItemCostUsesRawPrecisionWhileAvgPriceIsRoundedSeparately() {
        createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000.75"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> item = itemsOf(bodyOf(result)).get(0);
        assertThat(item.get("avgPrice")).isEqualTo("60001");
        assertThat(item.get("cost")).isEqualTo("600008");
    }

    /**
     * WireMock 스텁 없이 실제 시세 조회 실패 경로(PriceService.quoteForPortfolio()의 Optional.empty())를
     * 태운다 — 그래서 evaluationKrw/unrealizedPnl/weight가 필드 자체는 존재하되 값은 null이어야 한다.
     * 티커는 이 테스트 전용으로 고른다: "005930"을 쓰면 AssetPriceIntegrationTest가 같은 티커로 성공
     * 응답을 Redis에 캐싱해두는 시나리오와 전체 스위트 실행 시 충돌해 이 테스트가 캐시된 값을 그대로
     * 돌려받아 실패한다(Redis는 클래스 전체가 공유하는 Testcontainer, `./gradlew test` 전체 실행 시
     * 실측된 간헐적 실패 — AssetPriceIntegrationTest의 티커 선정 주석과 동일한 원인).
     */
    @Test
    void portfolioItemAndTopLevelValuationFieldsArePresentButNull() {
        createAsset(tokenA, stockRequest("PORTFOLIO-NULLQ", "이름없는종목", "10", "60000"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);

        assertThat(body.containsKey("totalEvaluationKrw")).isTrue();
        assertThat(body.get("totalEvaluationKrw")).isNull();
        assertThat(body.containsKey("totalUnrealizedPnl")).isTrue();
        assertThat(body.get("totalUnrealizedPnl")).isNull();

        Map<String, Object> item = itemsOf(body).get(0);
        assertThat(item.containsKey("evaluationKrw")).isTrue();
        assertThat(item.get("evaluationKrw")).isNull();
        assertThat(item.containsKey("unrealizedPnl")).isTrue();
        assertThat(item.get("unrealizedPnl")).isNull();
        assertThat(item.containsKey("weight")).isTrue();
        assertThat(item.get("weight")).isNull();
    }

    /**
     * STOCK/COIN/CASH(KRW)/CASH(USD)가 섞인 포트폴리오를 실제 WireMock(업비트·공공데이터포털·환율) 경로로
     * 검증한다 — 계산식 자체는 PortfolioServiceTest.computesEvaluationPnlAndWeightAcrossMixedAssetTypes와
     * 같은 종류이되 이번엔 HTTP를 실제로 태운다. 삼성전자(STOCK) 현재가 70000원×10주=700000, 원가 600000
     * → 손익 +100000. 코인 현재가 90000000원×0.1=9000000, 원가 8000000 → 손익 +1000000. KRW 현금
     * 500000은 그대로 평가금액=원가, 손익 0. USD 현금 100(평단가 1 고정)은 환율 1350.05→반올림 1350원
     * 적용 시 evaluationKrw=135000, costKrw=100×1350=135000 → 손익 0. total = 700000+9000000+500000+135000
     * = 10335000, 삼성전자 weight = 700000/10335000*100 ≈ 6.77.
     *
     * <p>CASH(USD)의 캐시 키(price:CASH:USD)는 티커가 아닌 통화 단위라 AssetPriceIntegrationTest의
     * getPriceForCashUsdAssetReturnsExchangeRate가 이미 채워둔 캐시(같은 Redis 컨테이너를 전체 스위트가
     * 공유)와 충돌할 수 있다 — 그 테스트와 똑같은 환율(1350.05)을 스텁해 어느 쪽이 먼저 캐시를 채우든
     * 결과가 달라지지 않게 한다.
     */
    @Test
    void portfolioComputesEvaluationPnlWeightAndTotalsAcrossMixedAssetTypesViaExternalPriceApis() {
        stockWireMock.stubFor(get(urlEqualTo(
                "/getStockPriceInfo?serviceKey=test-service-key&numOfRows=1&pageNo=1&resultType=json&likeSrtnCd=PFV1STOCK"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[{"basDt":"20260901","srtnCd":"PFV1STOCK","clpr":"70000"}]}}}}
                                """)));
        upbitWireMock.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-PFV1COIN"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"market\":\"KRW-PFV1COIN\",\"trade_price\":90000000.00000000}]")));
        exchangeRateWireMock.stubFor(get(urlEqualTo("/v1/currencies/usd.json"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"date\":\"2026-08-31\",\"usd\":{\"krw\":1350.05}}")));

        createAsset(tokenA, stockRequest("PFV1STOCK", "삼성전자", "10", "60000"));
        createAsset(tokenA, coinRequest("KRW-PFV1COIN", "비트코인", "0.1", "80000000"));
        createAsset(tokenA, cashRequest("PFV1CASHKRW", "원화 예수금", "500000", "1"));
        createAsset(tokenA, cashRequestWithCurrency("PFV1CASHUSD", "달러 예수금", "100", "1", "USD"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        List<Map<String, Object>> items = itemsOf(body);
        assertThat(items).hasSize(4);

        Map<String, Object> stock = itemByTicker(items, "PFV1STOCK");
        assertThat(new BigDecimal((String) stock.get("evaluationKrw"))).isEqualByComparingTo("700000");
        assertThat(new BigDecimal((String) stock.get("unrealizedPnl"))).isEqualByComparingTo("100000");
        assertThat(new BigDecimal((String) stock.get("weight"))).isEqualByComparingTo("6.77");

        Map<String, Object> coin = itemByTicker(items, "KRW-PFV1COIN");
        assertThat(new BigDecimal((String) coin.get("evaluationKrw"))).isEqualByComparingTo("9000000");
        assertThat(new BigDecimal((String) coin.get("unrealizedPnl"))).isEqualByComparingTo("1000000");
        assertThat(new BigDecimal((String) coin.get("weight"))).isEqualByComparingTo("87.08");

        Map<String, Object> cashKrw = itemByTicker(items, "PFV1CASHKRW");
        assertThat(new BigDecimal((String) cashKrw.get("evaluationKrw"))).isEqualByComparingTo("500000");
        assertThat(new BigDecimal((String) cashKrw.get("unrealizedPnl"))).isEqualByComparingTo("0");
        assertThat(new BigDecimal((String) cashKrw.get("weight"))).isEqualByComparingTo("4.84");

        Map<String, Object> cashUsd = itemByTicker(items, "PFV1CASHUSD");
        assertThat(new BigDecimal((String) cashUsd.get("evaluationKrw"))).isEqualByComparingTo("135000");
        assertThat(new BigDecimal((String) cashUsd.get("unrealizedPnl"))).isEqualByComparingTo("0");
        assertThat(new BigDecimal((String) cashUsd.get("weight"))).isEqualByComparingTo("1.31");

        assertThat(new BigDecimal((String) body.get("totalEvaluationKrw"))).isEqualByComparingTo("10335000");
        assertThat(new BigDecimal((String) body.get("totalUnrealizedPnl"))).isEqualByComparingTo("1100000");
    }

    /**
     * quoteForPortfolio()는 사용자당 초당 1건 Throttle을 적용하지 않는다(Task 023 사용자 확정 정책) —
     * COIN 5개(전부 서로 다른 캐시 키)를 한 번의 GET /v1/portfolio 호출로 조회해도 429가 전혀 없이
     * 전부 200으로 평가금액이 채워짐을 실증한다. 개별 자산의 정확한 계산식은 위 mixed-asset 테스트가
     * 이미 검증했으므로 여기서는 "5건 모두 evaluationKrw가 non-null"만 확인한다.
     */
    @Test
    void portfolioPricesAllAssetsWithoutThrottlingWhenAssetCountExceedsPerRequestLimit() {
        String[] tickers = {"KRW-PFT1", "KRW-PFT2", "KRW-PFT3", "KRW-PFT4", "KRW-PFT5"};
        for (String ticker : tickers) {
            upbitWireMock.stubFor(get(urlEqualTo("/v1/ticker?markets=" + ticker))
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                            .withBody("[{\"market\":\"" + ticker + "\",\"trade_price\":1000000.00000000}]")));
            createAsset(tokenA, coinRequest(ticker, "코인-" + ticker, "1", "900000"));
        }

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        List<Map<String, Object>> items = itemsOf(bodyOf(result));
        assertThat(items).hasSize(5);
        for (String ticker : tickers) {
            Map<String, Object> item = itemByTicker(items, ticker);
            assertThat(item.get("evaluationKrw")).isNotNull();
        }
    }

    /**
     * COIN 하나는 WireMock이 500을 반환(외부 API 장애)하고 STOCK 하나는 정상 응답인 혼합 포트폴리오 —
     * 실패한 COIN 항목만 evaluationKrw/unrealizedPnl/weight가 null로 남고, 성공한 STOCK 항목과
     * totalEvaluationKrw/totalUnrealizedPnl은 성공한 자산(STOCK)만으로 정상 계산된다(사용자 확정 정책).
     * STOCK: 현재가 25000원×5주=125000, 원가 100000 → 손익 +25000. 성공한 자산이 STOCK 하나뿐이라
     * weight는 정확히 100.00이어야 한다.
     */
    @Test
    void portfolioLeavesOnlyFailedAssetNullWhenOnePriceLookupFails() {
        upbitWireMock.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-PFV3FAIL"))
                .willReturn(aResponse().withStatus(500)));
        stockWireMock.stubFor(get(urlEqualTo(
                "/getStockPriceInfo?serviceKey=test-service-key&numOfRows=1&pageNo=1&resultType=json&likeSrtnCd=PFV3STOCK"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[{"basDt":"20260901","srtnCd":"PFV3STOCK","clpr":"25000"}]}}}}
                                """)));

        createAsset(tokenA, coinRequest("KRW-PFV3FAIL", "장애코인", "1", "1000000"));
        createAsset(tokenA, stockRequest("PFV3STOCK", "정상종목", "5", "20000"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        List<Map<String, Object>> items = itemsOf(body);

        Map<String, Object> failedCoin = itemByTicker(items, "KRW-PFV3FAIL");
        assertThat(failedCoin.get("evaluationKrw")).isNull();
        assertThat(failedCoin.get("unrealizedPnl")).isNull();
        assertThat(failedCoin.get("weight")).isNull();

        Map<String, Object> stock = itemByTicker(items, "PFV3STOCK");
        assertThat(new BigDecimal((String) stock.get("evaluationKrw"))).isEqualByComparingTo("125000");
        assertThat(new BigDecimal((String) stock.get("unrealizedPnl"))).isEqualByComparingTo("25000");
        assertThat(new BigDecimal((String) stock.get("weight"))).isEqualByComparingTo("100.00");

        assertThat(new BigDecimal((String) body.get("totalEvaluationKrw"))).isEqualByComparingTo("125000");
        assertThat(new BigDecimal((String) body.get("totalUnrealizedPnl"))).isEqualByComparingTo("25000");
    }

    /**
     * STOCK/COIN 시세는 자산의 currency와 무관하게 항상 KRW 환산액(업비트 KRW 마켓, 국내 시세)이다.
     * 자산을 USD로 등록하면(currency=USD, cost는 USD 스케일 4로 저장) evaluationKrw(KRW)와 cost(USD)의
     * 통화 단위가 맞지 않아 unrealizedPnl을 신뢰성 있게 계산할 수 없으므로 null로 남아야 한다(Task 023
     * Major 3, "거짓 숫자를 내보내지 않는다" 원칙). evaluationKrw 자체는 유효한 값이라 정상 계산되고,
     * weight도 다른 자산과의 비중 분모(evaluationKrw 기준)에 정상 포함되어야 한다.
     * USD 주식: 현재가 70000원×10주=700000(evaluationKrw). KRW 현금 300000은 그대로 평가금액=원가.
     * total = 700000+300000=1000000 → USD 주식 weight=70.00, 현금 weight=30.00.
     */
    @Test
    void portfolioLeavesUnrealizedPnlNullForNonKrwStockEvenWhenPriceLookupSucceeds() {
        stockWireMock.stubFor(get(urlEqualTo(
                "/getStockPriceInfo?serviceKey=test-service-key&numOfRows=1&pageNo=1&resultType=json&likeSrtnCd=PFV4USDSTOCK"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[{"basDt":"20260901","srtnCd":"PFV4USDSTOCK","clpr":"70000"}]}}}}
                                """)));

        createAsset(tokenA, stockRequestWithCurrency("PFV4USDSTOCK", "달러표시종목", "10", "100.0000", "USD"));
        createAsset(tokenA, cashRequest("PFV4CASHKRW", "원화 예수금", "300000", "1"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        List<Map<String, Object>> items = itemsOf(body);

        Map<String, Object> usdStock = itemByTicker(items, "PFV4USDSTOCK");
        assertThat(usdStock.get("evaluationKrw")).isNotNull();
        assertThat(new BigDecimal((String) usdStock.get("evaluationKrw"))).isEqualByComparingTo("700000");
        assertThat(usdStock.get("unrealizedPnl")).isNull();
        assertThat(new BigDecimal((String) usdStock.get("weight"))).isEqualByComparingTo("70.00");

        Map<String, Object> cashKrw = itemByTicker(items, "PFV4CASHKRW");
        assertThat(new BigDecimal((String) cashKrw.get("weight"))).isEqualByComparingTo("30.00");
    }

    /** items는 GET /v1/assets와 동일하게 id DESC(최신 등록순)로 응답한다(code-reviewer 2차 검증 지적). */
    @Test
    void portfolioReturnsItemsInMostRecentlyRegisteredFirstOrder() {
        createAsset(tokenA, stockRequest("A001", "종목1", "1", "1000"));
        createAsset(tokenA, stockRequest("A002", "종목2", "1", "1000"));
        createAsset(tokenA, stockRequest("A003", "종목3", "1", "1000"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        List<Map<String, Object>> items = itemsOf(bodyOf(result));
        assertThat(items).extracting(item -> item.get("ticker"))
                .containsExactly("A003", "A002", "A001");
    }

    @Test
    void portfolioOnlyReturnsRequestingUsersAssets() {
        createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenB);

        assertThat(result).hasStatusOk();
        assertThat(itemsOf(bodyOf(result))).isEmpty();
    }

    @Test
    void portfolioWithoutTokenReturnsUnauthorized() {
        assertThat(mvc.get().uri("/v1/portfolio").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("UNAUTHORIZED");
    }

    private MvcTestResult signup(String email, String password) {
        return mvc.post().uri("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))
                .exchange();
    }

    private MvcTestResult createAsset(String token, String body) {
        return mvc.post().uri("/v1/assets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult authorizedGet(String uri, String token) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private String accessTokenOf(MvcTestResult result) {
        return (String) bodyOf(result).get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> totalCostByCurrencyOf(Map<String, Object> body) {
        return (Map<String, String>) body.get("totalCostByCurrency");
    }

    private Map<String, Object> itemByTicker(List<Map<String, Object>> items, String ticker) {
        return items.stream().filter(item -> ticker.equals(item.get("ticker"))).findFirst()
                .orElseThrow(() -> new IllegalStateException("ticker " + ticker + " not found in items"));
    }

    private Map<String, Object> itemByTickerAndCost(List<Map<String, Object>> items, String ticker, String cost) {
        return items.stream()
                .filter(item -> ticker.equals(item.get("ticker")) && cost.equals(item.get("cost")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ticker " + ticker + " with cost " + cost + " not found in items"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(MvcTestResult result) {
        try {
            return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stockRequest(String ticker, String name, String quantity, String avgPrice) {
        return stockRequestWithCurrency(ticker, name, quantity, avgPrice, "KRW");
    }

    private static String stockRequestWithCurrency(String ticker, String name, String quantity, String avgPrice,
            String currency) {
        return """
                {"ticker":"%s","name":"%s","assetType":"STOCK","currency":"%s","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, currency, quantity, avgPrice);
    }

    private static String coinRequest(String ticker, String name, String quantity, String avgPrice) {
        return """
                {"ticker":"%s","name":"%s","assetType":"COIN","currency":"KRW","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, quantity, avgPrice);
    }

    private static String cashRequest(String ticker, String name, String quantity, String avgPrice) {
        return cashRequestWithCurrency(ticker, name, quantity, avgPrice, "KRW");
    }

    private static String cashRequestWithCurrency(String ticker, String name, String quantity, String avgPrice,
            String currency) {
        return """
                {"ticker":"%s","name":"%s","assetType":"CASH","currency":"%s","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, currency, quantity, avgPrice);
    }
}
