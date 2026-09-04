package com.allfolio;

import com.allfolio.domain.repository.UserRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
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
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 021 — GET /v1/assets/{id}/price. COIN은 업비트, CASH(USD)는 환율 API로
 * 라우팅되므로 WireMock 서버 두 개를 동시에 띄운다(UpbitPriceClientTest/ExchangeRateClientTest 패턴 결합).
 */
@AutoConfigureMockMvc
class AssetPriceIntegrationTest extends AbstractIntegrationTest {

    private static WireMockServer upbitWireMock;
    private static WireMockServer exchangeRateWireMock;
    private static WireMockServer stockWireMock;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

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

    @BeforeEach
    void setUp() {
        upbitWireMock.resetAll();
        exchangeRateWireMock.resetAll();
        stockWireMock.resetAll();
        userRepository.deleteAll();
        tokenA = accessTokenOf(signup("trader-a@example.com", "correct-horse-battery"));
        tokenB = accessTokenOf(signup("trader-b@example.com", "correct-horse-battery"));
    }

    @Test
    void getPriceForCoinAssetReturnsUpbitPrice() {
        upbitWireMock.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-BTC"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"market\":\"KRW-BTC\",\"trade_price\":107747000.00000000}]")));
        String assetId = idOf(createAsset(tokenA, coinRequest("KRW-BTC", "비트코인", "1", "100000000")));

        MvcTestResult result = authorizedGet("/v1/assets/" + assetId + "/price", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("amount")).isEqualTo("107747000.00000000");
        assertThat(body.get("currency")).isEqualTo("KRW");
        assertThat(body.get("asOf")).isNotNull();
        assertThat(body.get("isStale")).isEqualTo(false);
    }

    /**
     * 캐시 키(price:COIN:{ticker})는 자산이 아닌 종목 단위라, 캐시 미스인 서로 다른 종목을 같은
     * 사용자가 연이어 조회하면 두 번째 요청에서 사용자당 초당 1건(기본값) 한도를 넘는다. 캐시가
     * 개입하지 않는 경로라 Throttle 설정을 오버라이드하지 않고도 기본값 그대로 재현 가능하다.
     * 티커는 이 테스트 전용으로 다른 테스트와 겹치지 않게 고른다 — Redis 캐시는 클래스 전체가
     * 공유하는 컨테이너라 겹치는 티커를 쓰면 다른 테스트가 채워둔 캐시에 영향을 받는다.
     */
    @Test
    void getPriceExceedingThrottleLimitReturnsRateLimited() {
        upbitWireMock.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-THR1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"market\":\"KRW-THR1\",\"trade_price\":100000000.00000000}]")));
        upbitWireMock.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-THR2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"market\":\"KRW-THR2\",\"trade_price\":5000000.00000000}]")));
        String assetId1 = idOf(createAsset(tokenA, coinRequest("KRW-THR1", "코인1", "1", "100000000")));
        String assetId2 = idOf(createAsset(tokenA, coinRequest("KRW-THR2", "코인2", "1", "5000000")));

        assertThat(authorizedGet("/v1/assets/" + assetId1 + "/price", tokenA)).hasStatusOk();

        assertThat(authorizedGet("/v1/assets/" + assetId2 + "/price", tokenA))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("PRICE_RATE_LIMITED");
    }

    /**
     * coin-fresh-ttl 기본값(10초)이 실제로 지날 때까지 기다려 stale 전환을 재현한다 — 이 테스트만을
     * 위해 @DynamicPropertySource로 freshTtl을 짧게 오버라이드하면 이 클래스가 별도 Spring 컨텍스트로
     * 분리되는데, PostgreSQL Testcontainer를 여러 컨텍스트가 동시에 공유하면 컨텍스트마다 별도
     * HikariCP 풀이 열려 "FATAL: sorry, too many clients already"로 전체 스위트가 실패하는 것을
     * 실측으로 확인했다 — 기존 컨텍스트를 그대로 재사용해 이 문제를 피한다.
     */
    @Test
    void getPriceFallsBackToStaleCacheWhenExternalApiFailsAfterFreshTtlElapsed() throws InterruptedException {
        upbitWireMock.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-STALE"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"market\":\"KRW-STALE\",\"trade_price\":50000000.00000000}]")));
        String assetId = idOf(createAsset(tokenA, coinRequest("KRW-STALE", "스테일코인", "1", "40000000")));

        assertThat(authorizedGet("/v1/assets/" + assetId + "/price", tokenA)).hasStatusOk();

        Thread.sleep(10_500); // allfolio.price-cache.coin-fresh-ttl(10s) 경과를 보장

        upbitWireMock.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-STALE"))
                .willReturn(aResponse().withStatus(500)));

        MvcTestResult result = authorizedGet("/v1/assets/" + assetId + "/price", tokenA);

        assertThat(result).hasStatus(HttpStatus.PARTIAL_CONTENT);
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("amount")).isEqualTo("50000000.00000000");
        assertThat(body.get("isStale")).isEqualTo(true);
    }

    /** 캐시가 신선하면 두 번째 요청은 외부 API(WireMock)를 다시 부르지 않고 캐시값을 그대로 반환한다. */
    @Test
    void getPriceReturnsCachedValueWithoutCallingExternalApiOnSecondRequest() {
        upbitWireMock.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-CACHEHIT"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"market\":\"KRW-CACHEHIT\",\"trade_price\":30000000.00000000}]")));
        String assetId = idOf(createAsset(tokenA, coinRequest("KRW-CACHEHIT", "캐시히트코인", "1", "20000000")));

        assertThat(authorizedGet("/v1/assets/" + assetId + "/price", tokenA)).hasStatusOk();

        MvcTestResult result = authorizedGet("/v1/assets/" + assetId + "/price", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("amount")).isEqualTo("30000000.00000000");
        assertThat(body.get("isStale")).isEqualTo(false);
        upbitWireMock.verify(1, getRequestedFor(urlEqualTo("/v1/ticker?markets=KRW-CACHEHIT")));
    }

    @Test
    void getPriceForCashUsdAssetReturnsExchangeRate() {
        exchangeRateWireMock.stubFor(get(urlEqualTo("/v1/currencies/usd.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"date\":\"2026-08-31\",\"usd\":{\"krw\":1350.05}}")));
        String assetId = idOf(createAsset(tokenA, cashRequest("USD-CASH", "달러 현금", "USD", "100")));

        MvcTestResult result = authorizedGet("/v1/assets/" + assetId + "/price", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        // 스케일은 자산 통화(USD)가 아닌 실제 응답 통화(KRW, 0자리) 기준이다 — currency와 scale이
        // 서로 다른 기준을 쓰는 자기모순(예: amount에 소수점이 있는데 currency는 KRW)을 피하기 위함.
        assertThat(body.get("currency")).isEqualTo("KRW");
        assertThat(body.get("amount")).isEqualTo("1350");
    }

    @Test
    void getPriceForCashKrwAssetReturnsPriceNotApplicable() {
        String assetId = idOf(createAsset(tokenA, cashRequest("KRW-CASH", "현금", "KRW", "100000")));

        assertThat(authorizedGet("/v1/assets/" + assetId + "/price", tokenA))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("PRICE_NOT_APPLICABLE");
    }

    @Test
    void getPriceForStockAssetReturnsPublicDataPortalPrice() {
        stockWireMock.stubFor(get(urlEqualTo(
                "/getStockPriceInfo?serviceKey=test-service-key&numOfRows=1&pageNo=1&resultType=json&likeSrtnCd=005930"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[{"basDt":"20260831","srtnCd":"005930","isinCd":"KR7005930003","itmsNm":"삼성전자","mrktCtg":"KOSPI","clpr":"260000"}]}}}}
                                """)));
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "70000")));

        MvcTestResult result = authorizedGet("/v1/assets/" + assetId + "/price", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("amount")).isEqualTo("260000");
        assertThat(body.get("currency")).isEqualTo("KRW");
        assertThat(body.get("asOf")).isNotNull();
    }

    @Test
    void getPriceForOtherUsersAssetReturnsAssetNotFound() {
        String assetId = idOf(createAsset(tokenA, coinRequest("KRW-BTC", "비트코인", "1", "100000000")));

        assertThat(authorizedGet("/v1/assets/" + assetId + "/price", tokenB))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    void getPriceWithoutAuthenticationReturnsUnauthorized() {
        String assetId = idOf(createAsset(tokenA, coinRequest("KRW-BTC", "비트코인", "1", "100000000")));

        MvcTestResult result = mvc.get().uri("/v1/assets/" + assetId + "/price").exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
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

    private String idOf(MvcTestResult result) {
        return (String) bodyOf(result).get("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(MvcTestResult result) {
        try {
            return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String coinRequest(String ticker, String name, String quantity, String avgPrice) {
        return """
                {"ticker":"%s","name":"%s","assetType":"COIN","currency":"KRW","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, quantity, avgPrice);
    }

    private static String cashRequest(String ticker, String name, String currency, String avgPrice) {
        return """
                {"ticker":"%s","name":"%s","assetType":"CASH","currency":"%s","quantity":"1","avgPrice":"%s"}
                """.formatted(ticker, name, currency, avgPrice);
    }

    private static String stockRequest(String ticker, String name, String quantity, String avgPrice) {
        return """
                {"ticker":"%s","name":"%s","assetType":"STOCK","currency":"KRW","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, quantity, avgPrice);
    }
}
