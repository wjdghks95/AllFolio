package com.allfolio.infra.price;

import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.DailyBar;
import com.allfolio.domain.Price;
import com.allfolio.domain.SearchResult;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.TickerNotFoundException;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/ROADMAP.md Task 025 — 미국 주식(STOCK+USD) 시세 클라이언트(Twelve Data {@code /quote}).
 *
 * <p>WireMock 스텁 스키마는 실제 API 키로 curl 검증을 마쳤다(2026-09-08):
 * <ul>
 *   <li>정상 응답 예시(AAPL): {@code close}는 따옴표 붙은 문자열("319.97000"), {@code currency}는
 *       "USD", {@code last_quote_at}은 마지막 체결 시각(Unix epoch, 초)이다.</li>
 *   <li>존재하지 않는 심볼은 공공데이터포털·Upbit와 달리 **HTTP 404** + {@code {"code":404,
 *       "message":"...","status":"error"}} 바디로 온다(200+에러 패턴이 아님).</li>
 *   <li>인증 실패는 **HTTP 401** + 동일 스키마({@code {"code":401,...}})로 온다.</li>
 *   <li>실제 구현이 쓰는 헤더 인증({@code Authorization: apikey ...})도 curl로 재검증했다
 *       (2026-09-09, 코드 리뷰 후속) — HTTP 200 확인. 아래 정상 응답 테스트는 이 헤더가 실제로
 *       요청에 담겨 나가는지까지 WireMock으로 검증한다.</li>
 * </ul>
 */
class TwelveDataClientTest extends AbstractIntegrationTest {

    private static final String TICKER = "AAPL";
    private static final String REQUEST_PATH = "/quote?symbol=" + TICKER;

    private static WireMockServer wireMockServer;

    @Autowired
    private TwelveDataClient twelveDataClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @DynamicPropertySource
    static void twelveDataProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.twelvedata.base-url", () -> "http://localhost:" + wireMockServer.port());
        // 실제 환경변수(ALLFOLIO_TWELVEDATA_API_KEY) 유무에 따라 빈 문자열이 될 수 있는 값에
        // 헤더 매칭을 의존시키면 CI 환경별로 우연히 통과/실패가 갈린다 — 결정적 검증을 위해
        // 고정된 더미 값을 명시적으로 주입한다.
        registry.add("allfolio.twelvedata.api-key", () -> "test-api-key");
    }

    @BeforeEach
    void resetStubsAndCircuitBreaker() {
        wireMockServer.resetAll();
        circuitBreakerRegistry.circuitBreaker("twelvedata").reset();
    }

    @Test
    void getPriceMapsCloseCurrencyAndLastQuoteAtFromQuoteResponse() {
        wireMockServer.stubFor(get(urlEqualTo(REQUEST_PATH))
                // 인증이 쿼리 파라미터가 아닌 헤더(Authorization: apikey ...)로 실제로 담겨 나가는지
                // 검증한다 — 이 매칭이 없으면 헤더 형식에 오타가 나거나 인증 자체가 빠져도 테스트가
                // 계속 초록불로 통과한다(코드 리뷰 Major 이슈, 2026-09-09).
                .withHeader("Authorization", equalTo("apikey test-api-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "symbol": "AAPL",
                                  "name": "Apple Inc.",
                                  "exchange": "NASDAQ",
                                  "mic_code": "XNGS",
                                  "currency": "USD",
                                  "datetime": "2026-09-04",
                                  "timestamp": 1788528600,
                                  "last_quote_at": 1788551940,
                                  "open": "328.31000",
                                  "high": "328.92999",
                                  "low": "317.85999",
                                  "close": "319.97000",
                                  "volume": "39551800",
                                  "previous_close": "328.20999",
                                  "is_market_open": false
                                }
                                """)));

        Price price = twelveDataClient.getPrice(TICKER);

        assertThat(price.amount()).isEqualByComparingTo(new BigDecimal("319.97000"));
        assertThat(price.currency()).isEqualTo("USD");
        assertThat(price.asOf()).isEqualTo(Instant.ofEpochSecond(1788551940));
    }

    @Test
    void getPriceThrowsExternalPriceApiExceptionOnServerError() {
        wireMockServer.stubFor(get(urlEqualTo(REQUEST_PATH))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> twelveDataClient.getPrice(TICKER))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    @Test
    void getPriceThrowsExternalPriceApiExceptionOnTimeout() {
        wireMockServer.stubFor(get(urlEqualTo(REQUEST_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5000)));

        assertThatThrownBy(() -> twelveDataClient.getPrice(TICKER))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    /**
     * 실제로 존재하지 않는 심볼(ZZZZINVALID)로 호출해 확인한 진짜 응답(2026-09-08):
     * HTTP 404, {@code {"code":404,"message":"**symbol** or **figi** parameter is missing or
     * invalid...","status":"error"}}. TickerNotFoundException으로 구분해 Circuit Breaker 실패
     * 집계에서 제외한다(application.yml ignore-exceptions).
     */
    @Test
    void getPriceThrowsTickerNotFoundExceptionOnUnknownSymbol() {
        wireMockServer.stubFor(get(urlEqualTo(REQUEST_PATH))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "code": 404,
                                  "message": "**symbol** or **figi** parameter is missing or invalid.",
                                  "status": "error"
                                }
                                """)));

        assertThatThrownBy(() -> twelveDataClient.getPrice(TICKER))
                .isInstanceOf(TickerNotFoundException.class);
    }

    /**
     * 실제로 잘못된 API 키로 호출해 확인한 진짜 응답(2026-09-08): HTTP 401,
     * {@code {"code":401,"message":"**apikey** parameter is incorrect or not specified...",
     * "status":"error"}}. 404와 달리 클라이언트 입력 오류가 아니므로 ExternalPriceApiException으로
     * 처리해 Circuit Breaker 실패 집계에 그대로 반영한다.
     */
    @Test
    void getPriceThrowsExternalPriceApiExceptionOnAuthError() {
        wireMockServer.stubFor(get(urlEqualTo(REQUEST_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "code": 401,
                                  "message": "**apikey** parameter is incorrect or not specified.",
                                  "status": "error"
                                }
                                """)));

        assertThatThrownBy(() -> twelveDataClient.getPrice(TICKER))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    @Test
    void getPriceThrowsExternalPriceApiExceptionOnCurrencyMismatch() {
        wireMockServer.stubFor(get(urlEqualTo(REQUEST_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "symbol": "AAPL",
                                  "currency": "EUR",
                                  "close": "319.97000",
                                  "last_quote_at": 1788551940
                                }
                                """)));

        assertThatThrownBy(() -> twelveDataClient.getPrice(TICKER))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    @Test
    void getPriceThrowsExternalPriceApiExceptionOnSymbolMismatch() {
        wireMockServer.stubFor(get(urlEqualTo(REQUEST_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "symbol": "MSFT",
                                  "currency": "USD",
                                  "close": "319.97000",
                                  "last_quote_at": 1788551940
                                }
                                """)));

        assertThatThrownBy(() -> twelveDataClient.getPrice(TICKER))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    /**
     * 해외주식 일봉 시계열(F007) — {@code /time_series?interval=1day} 실제 curl 검증(2026-09-11):
     * {@code values[]}의 각 항목은 {@code datetime}(날짜만)·{@code open}/{@code high}/{@code low}/
     * {@code close}(모두 따옴표 붙은 문자열)를 담고 있고, 오래된 순서가 아니라 최신순(내림차순)으로
     * 온다(AAPL 5건 기준 "2026-09-10"이 첫 항목).
     */
    @Test
    void getDailySeriesMapsValuesToDailyBarInApiOrder() {
        wireMockServer.stubFor(get(urlEqualTo("/time_series?symbol=" + TICKER + "&interval=1day&outputsize=5"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "meta": {
                                    "symbol": "AAPL",
                                    "interval": "1day",
                                    "currency": "USD",
                                    "exchange_timezone": "America/New_York",
                                    "exchange": "NASDAQ",
                                    "mic_code": "XNGS",
                                    "type": "Common Stock"
                                  },
                                  "values": [
                                    {"datetime": "2026-09-10", "open": "316.67001", "high": "326.73999", "low": "316.51001", "close": "326.57001", "volume": "69925100"},
                                    {"datetime": "2026-09-09", "open": "315.48999", "high": "319.14999", "low": "309.89999", "close": "315.34000", "volume": "65640000"}
                                  ],
                                  "status": "ok"
                                }
                                """)));

        List<DailyBar> bars = twelveDataClient.getDailySeries(TICKER, 5);

        assertThat(bars).containsExactly(
                new DailyBar(LocalDate.of(2026, 9, 10), new BigDecimal("316.67001"), new BigDecimal("326.73999"),
                        new BigDecimal("316.51001"), new BigDecimal("326.57001")),
                new DailyBar(LocalDate.of(2026, 9, 9), new BigDecimal("315.48999"), new BigDecimal("319.14999"),
                        new BigDecimal("309.89999"), new BigDecimal("315.34000")));
    }

    /**
     * 실제로 존재하지 않는 심볼(ZZZZINVALID)로 {@code /time_series}를 호출해 확인한 진짜 응답
     * (2026-09-11): {@code /quote}와 동일하게 HTTP 404 + {@code {"code":404,...,"status":"error"}}.
     */
    @Test
    void getDailySeriesThrowsTickerNotFoundExceptionOnUnknownSymbol() {
        wireMockServer.stubFor(get(urlEqualTo("/time_series?symbol=" + TICKER + "&interval=1day&outputsize=5"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "code": 404,
                                  "message": "**symbol** or **figi** parameter is missing or invalid.",
                                  "status": "error"
                                }
                                """)));

        assertThatThrownBy(() -> twelveDataClient.getDailySeries(TICKER, 5))
                .isInstanceOf(TickerNotFoundException.class);
    }

    /**
     * 실제로 outputsize=5001(상한 5000 초과)로 호출해 확인한 진짜 응답(2026-09-11): HTTP 400 +
     * {@code {"code":400,"message":"Invalid outputsize provided: 5001. Accepts values in the
     * range from 1 to 5000 inclusive.","status":"error"}}. 클라이언트는 별도 사전 검증 없이 이
     * 4xx를 RestClient 기본 동작대로 예외化해 ExternalPriceApiException으로 변환한다.
     */
    @Test
    void getDailySeriesThrowsExternalPriceApiExceptionWhenOutputsizeExceedsLimit() {
        wireMockServer.stubFor(get(urlEqualTo("/time_series?symbol=" + TICKER + "&interval=1day&outputsize=5001"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "code": 400,
                                  "message": "Invalid outputsize provided: 5001. Accepts values in the range from 1 to 5000 inclusive.",
                                  "status": "error"
                                }
                                """)));

        assertThatThrownBy(() -> twelveDataClient.getDailySeries(TICKER, 5001))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    /**
     * docs/ROADMAP.md Task 026(통합 종목 검색) — {@code /symbol_search} 실제 curl 검증(2026-09-09):
     * "AAPL" 검색 결과에는 나스닥(Common Stock/USD)뿐 아니라 아르헨티나 CEDEAR·칠레 BVS·태국 SET 등
     * 전세계 상장이 섞여 온다({@code instrument_type}이 "Depositary Receipt"/"Mutual Fund"거나
     * {@code currency}가 USD가 아님) — {@code instrument_type=="Common Stock" && currency=="USD"}로
     * 걸러야 한다. 이 스텁은 실제 응답 구조를 축약해 나스닥 정상 항목·통화 불일치(Depositary
     * Receipt, ARS)·자산유형 불일치(Common Stock이지만 다른 나라 상장이 아닌 Mutual Fund) 3종을
     * 함께 담아, 필터링이 실제로 동작하는지 검증한다.
     */
    @Test
    void searchFiltersToUsCommonStockAndMapsToSearchResult() {
        wireMockServer.stubFor(get(urlEqualTo("/symbol_search?symbol=AAPL&outputsize=20"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": [
                                    {
                                      "symbol": "AAPL",
                                      "instrument_name": "Apple Inc.",
                                      "exchange": "NASDAQ",
                                      "mic_code": "XNGS",
                                      "instrument_type": "Common Stock",
                                      "country": "United States",
                                      "currency": "USD"
                                    },
                                    {
                                      "symbol": "AAPL",
                                      "instrument_name": "Apple Inc. CEDEAR",
                                      "exchange": "BCBA",
                                      "mic_code": "XBUE",
                                      "instrument_type": "Depositary Receipt",
                                      "country": "Argentina",
                                      "currency": "ARS"
                                    },
                                    {
                                      "symbol": "AAPLDXX",
                                      "instrument_name": "Barclays Bank PLC Note AAPLDXX",
                                      "exchange": "NASDAQ",
                                      "mic_code": "XNAS",
                                      "instrument_type": "Mutual Fund",
                                      "country": "United States",
                                      "currency": "USD"
                                    }
                                  ],
                                  "status": "ok"
                                }
                                """)));

        List<SearchResult> results = twelveDataClient.search("AAPL");

        assertThat(results).containsExactly(new SearchResult("AAPL", "Apple Inc.", AssetType.STOCK, "USD"));
    }

    /**
     * 실측(2026-09-09)으로 확정한 핵심 포인트 — {@code /symbol_search}의 "결과 없음"은
     * {@code /quote}의 404와 달리 **정상 HTTP 200 + {@code {"data":[],"status":"ok"}}**로 온다
     * (실제 존재하지 않는 질의어 "ZZZZINVALIDNOTHING"으로 curl 직접 재현 확인).
     */
    @Test
    void searchReturnsEmptyListWhenNoMatches() {
        wireMockServer.stubFor(get(urlEqualTo("/symbol_search?symbol=ZZZZINVALIDNOTHING&outputsize=20"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "data": [],
                                  "status": "ok"
                                }
                                """)));

        assertThat(twelveDataClient.search("ZZZZINVALIDNOTHING")).isEmpty();
    }

    /**
     * 실측(2026-09-09)으로는 {@code /symbol_search}가 잘못된/누락된 apikey로도 200을 반환해
     * 인증 실패를 실제로 재현하지 못했다(공개 참조 데이터 엔드포인트로 추정) — 그럼에도 공식 문서가
     * 401을 명시하고 있어, 방어적으로 4xx를 받으면 {@link ExternalPriceApiException}으로 변환되는지
     * WireMock 스텁으로 검증해둔다.
     */
    @Test
    void searchThrowsExternalPriceApiExceptionOnAuthError() {
        wireMockServer.stubFor(get(urlEqualTo("/symbol_search?symbol=AAPL&outputsize=20"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "code": 401,
                                  "message": "**apikey** parameter is incorrect or not specified.",
                                  "status": "error"
                                }
                                """)));

        assertThatThrownBy(() -> twelveDataClient.search("AAPL"))
                .isInstanceOf(ExternalPriceApiException.class);
    }
}
