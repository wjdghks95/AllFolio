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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.ObjectMapper;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /v1/assets/search 통합 테스트(docs/ROADMAP.md Task 026-D). STOCK+KRW(공공데이터포털)·COIN(업비트)
 * 라우팅을 WireMock 서버로 검증한다. JWT 인증, 400/401/429 에러 경로 포함.
 */
@AutoConfigureMockMvc
class SearchIntegrationTest extends AbstractIntegrationTest {

    private static WireMockServer stockWireMock;
    private static WireMockServer upbitWireMock;
    private static WireMockServer twelveDataWireMock;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private String token;

    @BeforeAll
    static void startWireMock() {
        stockWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        stockWireMock.start();
        upbitWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upbitWireMock.start();
        twelveDataWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        twelveDataWireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        stockWireMock.stop();
        upbitWireMock.stop();
        twelveDataWireMock.stop();
    }

    @DynamicPropertySource
    static void wireMockProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.stock.base-url", () -> "http://localhost:" + stockWireMock.port());
        registry.add("allfolio.upbit.base-url", () -> "http://localhost:" + upbitWireMock.port());
        registry.add("allfolio.twelvedata.base-url", () -> "http://localhost:" + twelveDataWireMock.port());
    }

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry.circuitBreaker("stock").reset();
        circuitBreakerRegistry.circuitBreaker("upbit").reset();
        stockWireMock.resetAll();
        upbitWireMock.resetAll();
        twelveDataWireMock.resetAll();
        userRepository.deleteAll();
        // 검색 캐시·Throttle 키를 초기화해 테스트 간 격리. keys()는 null을 반환할 수 있다.
        var searchKeys = stringRedisTemplate.keys("search:*");
        if (searchKeys != null) searchKeys.forEach(stringRedisTemplate::delete);
        var throttleKeys = stringRedisTemplate.keys("throttle:search:*");
        if (throttleKeys != null) throttleKeys.forEach(stringRedisTemplate::delete);
        token = accessTokenOf(signup("searcher@example.com", "correct-horse-battery"));
    }

    @Test
    void searchStockKrwReturnsResultList() {
        stockWireMock.stubFor(get(urlPathMatching("/getStockPriceInfo"))
                .withQueryParam("likeItmsNm", equalTo("삼성"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[{"basDt":"20260901","srtnCd":"005930","isinCd":"KR7005930003","itmsNm":"삼성전자","mrktCtg":"KOSPI","clpr":"70000"}]}}}}
                                """)));

        MvcTestResult result = authorizedGet("/v1/assets/search?assetType=STOCK&currency=KRW&q=삼성", token);

        assertThat(result).hasStatusOk();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = bodyListOf(result);
        assertThat(body).isNotEmpty();
        assertThat(body.get(0)).containsEntry("assetType", "STOCK");
        assertThat(body.get(0)).containsEntry("currency", "KRW");
        assertThat(body.get(0)).doesNotContainKey("currentPrice");
        assertThat(body.get(0)).doesNotContainKey("price");
    }

    @Test
    void searchCoinReturnsFilteredResultList() {
        upbitWireMock.stubFor(get(urlPathMatching("/v1/market/all"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {"market":"KRW-BTC","korean_name":"비트코인","english_name":"Bitcoin"},
                                  {"market":"KRW-ETH","korean_name":"이더리움","english_name":"Ethereum"},
                                  {"market":"KRW-XRP","korean_name":"리플","english_name":"Ripple"}
                                ]
                                """)));

        MvcTestResult result = authorizedGet("/v1/assets/search?assetType=COIN&currency=KRW&q=비트", token);

        assertThat(result).hasStatusOk();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = bodyListOf(result);
        assertThat(body).hasSize(1);
        assertThat(body.get(0)).containsEntry("name", "비트코인");
        assertThat(body.get(0)).containsEntry("assetType", "COIN");
    }

    @Test
    void searchWithNoResultsReturnsEmptyList() {
        stockWireMock.stubFor(get(urlPathMatching("/getStockPriceInfo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[]}}}}
                                """)));

        MvcTestResult result = authorizedGet("/v1/assets/search?assetType=STOCK&currency=KRW&q=없는종목", token);

        assertThat(result).hasStatusOk();
        @SuppressWarnings("unchecked")
        List<?> body = bodyListOf(result);
        assertThat(body).isEmpty();
    }

    @Test
    void searchWithoutAuthenticationReturnsUnauthorized() {
        MvcTestResult result = mvc.get().uri("/v1/assets/search?assetType=STOCK&currency=KRW&q=삼성").exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void searchStockUsdReturnsResultList() {
        twelveDataWireMock.stubFor(get(urlPathMatching("/symbol_search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":[{"symbol":"AAPL","instrument_name":"Apple Inc","exchange":"NASDAQ","currency":"USD","instrument_type":"Common Stock"}]}
                                """)));

        MvcTestResult result = authorizedGet(
                "/v1/assets/search?assetType=STOCK&currency=USD&q=AAPL", token);

        assertThat(result).hasStatusOk();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = bodyListOf(result);
        assertThat(body).isNotEmpty();
        assertThat(body.get(0)).containsEntry("assetType", "STOCK");
        assertThat(body.get(0)).containsEntry("currency", "USD");
        assertThat(body.get(0)).doesNotContainKey("price");
    }

    @Test
    void searchCoinWithUnsupportedCurrencyReturnsBadRequest() {
        MvcTestResult result = authorizedGet(
                "/v1/assets/search?assetType=COIN&currency=JPY&q=btc", token);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void searchWithCashAssetTypeReturnsBadRequest() {
        MvcTestResult result = authorizedGet("/v1/assets/search?assetType=CASH&currency=KRW&q=원화", token);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    /**
     * Redis에서 throttle:search:{userId} 키를 limit(30)+1=31로 직접 설정해 Throttle 초과를 재현한다.
     * DynamicPropertySource로 limit을 낮추면 별도 ApplicationContext가 생성돼 Testcontainer
     * 커넥션 폭발 문제(AbstractIntegrationTest 주석)가 생기므로 이 방법을 선택했다.
     *
     * [Minor 3] 루프 INCR 방식 대신 키를 직접 SET(31, TTL=10s)한다.
     * 루프 INCR은 실행 시간이 10초를 초과하면 PEXPIRE가 만료돼 카운터가 리셋될 수 있다.
     */
    @Test
    void searchExceedingThrottleReturns429() {
        stockWireMock.stubFor(get(urlPathMatching("/getStockPriceInfo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[]}}}}
                                """)));

        // 첫 번째 요청으로 throttle:search:{userId} 키를 Redis에 생성시킨다.
        authorizedGet("/v1/assets/search?assetType=STOCK&currency=KRW&q=test", token);

        // 생성된 키를 찾아 카운터를 limit(30)+1=31로 직접 덮어써 즉시 초과 상태로 만든다.
        var throttleKeys = stringRedisTemplate.keys("throttle:search:*");
        assertThat(throttleKeys).isNotNull().isNotEmpty();
        String throttleKey = throttleKeys.iterator().next();
        stringRedisTemplate.opsForValue().set(throttleKey, "31", Duration.ofSeconds(10));

        MvcTestResult result = authorizedGet("/v1/assets/search?assetType=STOCK&currency=KRW&q=삼성", token);

        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("SEARCH_RATE_LIMITED");
    }

    private MvcTestResult signup(String email, String password) {
        return mvc.post().uri("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))
                .exchange();
    }

    private MvcTestResult authorizedGet(String uri, String token) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private String accessTokenOf(MvcTestResult result) {
        return (String) bodyOf(result).get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(MvcTestResult result) {
        try {
            return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> bodyListOf(MvcTestResult result) {
        try {
            return objectMapper.readValue(result.getResponse().getContentAsString(), List.class);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
