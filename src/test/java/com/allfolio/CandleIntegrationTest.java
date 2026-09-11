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
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 028 「CandleService + REST 캔들 엔드포인트」 — GET /v1/assets/{id}/candles의
 * 컨트롤러→서비스→실제 Redis(Testcontainers) 배선을 검증하는 통합 테스트. COIN/STOCK 오케스트레이션의
 * 세부 분기(캐시 판정·락·확장 페이징)는 이미 {@code CandleServiceTest}(Mockito 단위 테스트)가 다루므로,
 * 여기서는 실제 컴포넌트가 서로 올바르게 연결돼 end-to-end로 동작하는지만 검증한다
 * (AssetPriceIntegrationTest와 동일한 책임 분리 원칙).
 */
@AutoConfigureMockMvc
class CandleIntegrationTest extends AbstractIntegrationTest {

    private static WireMockServer upbitWireMock;
    private static WireMockServer stockWireMock;
    private static WireMockServer twelveDataWireMock;

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
        stockWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        stockWireMock.start();
        twelveDataWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        twelveDataWireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        upbitWireMock.stop();
        stockWireMock.stop();
        twelveDataWireMock.stop();
    }

    @DynamicPropertySource
    static void candleProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.upbit.base-url", () -> "http://localhost:" + upbitWireMock.port());
        registry.add("allfolio.stock.base-url", () -> "http://localhost:" + stockWireMock.port());
        registry.add("allfolio.twelvedata.base-url", () -> "http://localhost:" + twelveDataWireMock.port());
        registry.add("allfolio.twelvedata.api-key", () -> "test-api-key");
    }

    @BeforeEach
    void setUp() {
        upbitWireMock.resetAll();
        stockWireMock.resetAll();
        twelveDataWireMock.resetAll();
        userRepository.deleteAll();
        tokenA = accessTokenOf(signup("candle-trader-a@example.com", "correct-horse-battery"));
        tokenB = accessTokenOf(signup("candle-trader-b@example.com", "correct-horse-battery"));
    }

    @Test
    void getCandlesForCoinDayIntervalReturnsUpbitDayCandles() {
        upbitWireMock.stubFor(get(urlPathEqualTo("/v1/candles/days"))
                .withQueryParam("market", equalTo("KRW-CDL1"))
                .withQueryParam("count", equalTo("200"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"market":"KRW-CDL1","candle_date_time_utc":"2026-09-10T00:00:00",
                                  "candle_date_time_kst":"2026-09-10T09:00:00","opening_price":100000000.0,
                                  "high_price":101000000.0,"low_price":99000000.0,"trade_price":100500000.0,
                                  "timestamp":1,"candle_acc_trade_price":1,"candle_acc_trade_volume":1,
                                  "prev_closing_price":1,"change_price":1,"change_rate":0.1}]
                                """)));
        String assetId = idOf(createAsset(tokenA, coinRequest("KRW-CDL1", "캔들코인", "1", "100000000")));

        MvcTestResult result = authorizedGet("/v1/assets/" + assetId + "/candles?interval=DAY", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("hasMoreHistory")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bars = (List<Map<String, Object>>) body.get("bars");
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).get("bucketStart")).isEqualTo("2026-09-10T00:00:00Z");
        assertThat(bars.get(0).get("open")).isEqualTo("100000000.00000000");
        assertThat(bars.get(0).get("close")).isEqualTo("100500000.00000000");
    }

    @Test
    void getCandlesForStockKrwDayIntervalReturnsPublicDataPortalDailyBars() {
        stockWireMock.stubFor(get(urlPathEqualTo("/getStockPriceInfo"))
                .withQueryParam("serviceKey", equalTo("test-service-key"))
                .withQueryParam("likeSrtnCd", equalTo("005930"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                                  "body":{"items":{"item":[
                                    {"basDt":"20260910","srtnCd":"005930","itmsNm":"삼성전자",
                                     "mkp":"71000","hipr":"72000","lopr":"70000","clpr":"71500"}
                                  ]}}}}
                                """)));
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "KRW", "10", "70000")));

        MvcTestResult result = authorizedGet("/v1/assets/" + assetId + "/candles?interval=DAY", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        // 스텁이 단 하루치만 응답하므로 caching 캡(10년)에는 못 미쳐 더 과거를 조회할 여지가 남는다.
        assertThat(body.get("hasMoreHistory")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bars = (List<Map<String, Object>>) body.get("bars");
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).get("bucketStart")).isEqualTo("2026-09-10");
        assertThat(bars.get(0).get("close")).isEqualTo("71500");
    }

    @Test
    void getCandlesForStockUsdDayIntervalReturnsTwelveDataDailyBars() {
        twelveDataWireMock.stubFor(get(urlPathEqualTo("/time_series"))
                .withQueryParam("symbol", equalTo("CDLU"))
                .withQueryParam("interval", equalTo("1day"))
                .withQueryParam("outputsize", equalTo("5000"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"meta":{"symbol":"CDLU","interval":"1day","currency":"USD"},
                                 "values":[{"datetime":"2026-09-10","open":"316.67","high":"326.74",
                                            "low":"316.51","close":"326.57"}],
                                 "status":"ok"}
                                """)));
        String assetId = idOf(createAsset(tokenA, stockRequest("CDLU", "테스트US", "USD", "5", "300")));

        MvcTestResult result = authorizedGet("/v1/assets/" + assetId + "/candles?interval=DAY", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bars = (List<Map<String, Object>>) body.get("bars");
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).get("bucketStart")).isEqualTo("2026-09-10");
        assertThat(bars.get(0).get("close")).isEqualTo("326.57");
    }

    @Test
    void getCandlesForCashAssetReturnsPriceNotApplicable() {
        String assetId = idOf(createAsset(tokenA, cashRequest("KRW-CASH-CDL", "현금", "KRW", "100000")));

        assertThat(authorizedGet("/v1/assets/" + assetId + "/candles?interval=DAY", tokenA))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("PRICE_NOT_APPLICABLE");
    }

    @Test
    void getCandlesForOtherUsersAssetReturnsAssetNotFound() {
        upbitWireMock.stubFor(get(urlPathEqualTo("/v1/candles/days"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));
        String assetId = idOf(createAsset(tokenA, coinRequest("KRW-CDL2", "캔들코인2", "1", "100000000")));

        assertThat(authorizedGet("/v1/assets/" + assetId + "/candles?interval=DAY", tokenB))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    void getCandlesWithUnsupportedIntervalReturnsValidationError() {
        String assetId = idOf(createAsset(tokenA, coinRequest("KRW-CDL3", "캔들코인3", "1", "100000000")));

        assertThat(authorizedGet("/v1/assets/" + assetId + "/candles?interval=MINUTE", tokenA))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void getCandlesForStockAssetWithMinuteIntervalReturnsValidationError() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "KRW", "10", "70000")));

        assertThat(authorizedGet("/v1/assets/" + assetId + "/candles?interval=minute1", tokenA))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void getCandlesWithInvalidBeforeReturnsValidationError() {
        String assetId = idOf(createAsset(tokenA, coinRequest("KRW-CDL4", "캔들코인4", "1", "100000000")));

        assertThat(authorizedGet("/v1/assets/" + assetId + "/candles?interval=DAY&before=not-a-date", tokenA))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void getCandlesWithoutAuthenticationReturnsUnauthorized() {
        String assetId = idOf(createAsset(tokenA, coinRequest("KRW-CDL5", "캔들코인5", "1", "100000000")));

        MvcTestResult result = mvc.get().uri("/v1/assets/" + assetId + "/candles?interval=DAY").exchange();

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

    private static String stockRequest(String ticker, String name, String currency, String quantity, String avgPrice) {
        return """
                {"ticker":"%s","name":"%s","assetType":"STOCK","currency":"%s","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, currency, quantity, avgPrice);
    }
}
