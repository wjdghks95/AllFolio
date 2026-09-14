package com.allfolio.infra.price;

import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Candle;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/ROADMAP.md Task 021 — 업비트 시세 클라이언트. {@code @CircuitBreaker}는 Spring AOP 프록시를
 * 통해서만 동작하므로 직접 생성이 아닌 Spring 컨텍스트에서 주입받은 빈으로 검증한다.
 */
class UpbitPriceClientTest extends AbstractIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private UpbitPriceClient upbitPriceClient;

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
    static void upbitProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.upbit.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @BeforeEach
    void resetStubsAndCircuitBreaker() {
        wireMockServer.resetAll();
        circuitBreakerRegistry.circuitBreaker("upbit").reset();
    }

    @Test
    void getPriceMapsTradePriceFromUpbitResponse() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-BTC"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"market\":\"KRW-BTC\",\"trade_price\":107747000.00000000}]")));

        Price price = upbitPriceClient.getPrice("KRW-BTC", "KRW");

        assertThat(price.amount()).isEqualByComparingTo(new BigDecimal("107747000.00000000"));
        assertThat(price.currency()).isEqualTo("KRW");
    }

    @Test
    void getPriceNormalizesTickerWithoutMarketPrefixUsingAssetCurrency() {
        // 사용자는 등록 화면에서 "BTC"처럼 심볼만 입력한다 — 업비트가 요구하는 마켓 코드
        // 접두어는 자산의 통화(currency)에서 그대로 가져와 붙인다(KRW 자산 → KRW-BTC).
        wireMockServer.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-BTC"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"market\":\"KRW-BTC\",\"trade_price\":107747000.00000000}]")));

        Price price = upbitPriceClient.getPrice("BTC", "KRW");

        assertThat(price.amount()).isEqualByComparingTo(new BigDecimal("107747000.00000000"));
    }

    @Test
    void getPriceNormalizesUsdCurrencyToUsdtMarket() {
        // 업비트는 "USD-BTC" 마켓을 지원하지 않는다(원화/USDT 마켓만 존재) — USD로 등록한
        // 코인 자산은 사실상 동일 가치인 USDT 마켓으로 대신 조회한다.
        wireMockServer.stubFor(get(urlEqualTo("/v1/ticker?markets=USDT-BTC"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"market\":\"USDT-BTC\",\"trade_price\":73000.00000000}]")));

        Price price = upbitPriceClient.getPrice("BTC", "USD");

        assertThat(price.amount()).isEqualByComparingTo(new BigDecimal("73000.00000000"));
        // 마켓 조회는 USDT로 우회하지만, 응답이 표현하는 도메인 통화는 자산에 등록된 "USD" 그대로다.
        assertThat(price.currency()).isEqualTo("USD");
    }

    /**
     * 회귀 방지(code-reviewer M1): 이미 마켓 접두어가 포함된 티커는 currency 인자와 어긋날 수
     * 있다(예: ticker="KRW-BTC"인데 자산 currency는 "USD"로 등록된 경우). 이때 응답 통화는
     * currency 인자가 아니라 실제로 조회한 마켓(KRW-BTC → KRW)에서 도출해야 한다 — 그렇지
     * 않으면 이미 원화인 시세를 PriceService.coinPrice()가 "USD"로 오인해 환율을 한 번 더
     * 곱해 약 1,300배 부풀린 평가금액이 나간다(Playwright 실측 결함과 동일 유형).
     */
    @Test
    void getPriceDerivesCurrencyFromActualMarketNotFromMismatchedCurrencyArgument() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-BTC"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"market\":\"KRW-BTC\",\"trade_price\":107747000.00000000}]")));

        Price price = upbitPriceClient.getPrice("KRW-BTC", "USD");

        assertThat(price.currency()).isEqualTo("KRW");
    }

    @Test
    void getPriceThrowsExternalPriceApiExceptionOnServerError() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-BTC"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> upbitPriceClient.getPrice("KRW-BTC", "KRW"))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    @Test
    void getPriceThrowsExternalPriceApiExceptionOnTimeout() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-BTC"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5000)));

        assertThatThrownBy(() -> upbitPriceClient.getPrice("KRW-BTC", "KRW"))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    /**
     * 통합 종목 검색(GET /v1/assets/search) COIN 분기용 전체 마켓 목록 조회(Task 026 서브태스크 3).
     * KRW-, USDT- 접두어 마켓만 SearchResult로 매핑하고, AllFolio가 지원하지 않는 통화의 코인마켓(BTC- 접두어 등)은
     * 제외해야 한다.
     */
    @Test
    void listMarketsMapsKrwAndUsdtMarketsAndExcludesUnsupportedQuoteCurrencies() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/market/all"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {"market":"KRW-BTC","korean_name":"비트코인","english_name":"Bitcoin"},
                                  {"market":"USDT-BTC","korean_name":"비트코인","english_name":"Bitcoin"},
                                  {"market":"BTC-ETH","korean_name":"이더리움","english_name":"Ethereum"}
                                ]
                                """)));

        List<SearchResult> markets = upbitPriceClient.listMarkets();

        assertThat(markets).containsExactlyInAnyOrder(
                new SearchResult("BTC", "비트코인", AssetType.COIN, "KRW"),
                new SearchResult("BTC", "비트코인", AssetType.COIN, "USD"));
    }

    @Test
    void listMarketsThrowsExternalPriceApiExceptionOnServerError() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/market/all"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> upbitPriceClient.listMarkets())
                .isInstanceOf(ExternalPriceApiException.class);
    }

    /**
     * docs/ROADMAP.md Task 028 「COIN 캔들 조회 클라이언트 확장」. 실제 응답에는 여기서 매핑하지
     * 않는 필드(prev_closing_price·change_price 등)가 더 있는데, 기존 {@link UpbitTickerResponse}가
     * 이미 같은 방식(전체 25개 필드 중 2개만 매핑)으로 실 API와 동작해온 전례를 그대로 따른다 —
     * 스텁 바디에도 매핑 대상 외 필드(candle_acc_trade_price)를 일부러 섞어 역직렬화가 깨지지
     * 않는지 함께 검증한다.
     */
    @Test
    void getDayCandlesMapsOhlcAndDerivesCandleAtFromUtcPeriodStart() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/candles/days"))
                .withQueryParam("market", equalTo("KRW-BTC"))
                .withQueryParam("count", equalTo("2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {"market":"KRW-BTC","candle_date_time_utc":"2026-09-11T00:00:00",
                                   "opening_price":104721000.0,"high_price":105242000.0,"low_price":104710000.0,
                                   "trade_price":105232000.0,"candle_acc_trade_price":19467426670.45949000},
                                  {"market":"KRW-BTC","candle_date_time_utc":"2026-09-10T00:00:00",
                                   "opening_price":106402000.0,"high_price":106575000.0,"low_price":104712000.0,
                                   "trade_price":104712000.0,"candle_acc_trade_price":98903659326.47980000}
                                ]
                                """)));

        List<Candle> candles = upbitPriceClient.getDayCandles("BTC", "KRW", 2);

        assertThat(candles).hasSize(2);
        Candle latest = candles.get(0);
        assertThat(latest.open()).isEqualByComparingTo("104721000.0");
        assertThat(latest.high()).isEqualByComparingTo("105242000.0");
        assertThat(latest.low()).isEqualByComparingTo("104710000.0");
        assertThat(latest.close()).isEqualByComparingTo("105232000.0");
        assertThat(latest.candleAt()).isEqualTo(LocalDateTime.of(2026, 9, 11, 0, 0).toInstant(ZoneOffset.UTC));
    }

    @Test
    void getMinuteCandlesUsesUnitInPath() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/candles/minutes/15"))
                .withQueryParam("market", equalTo("KRW-BTC"))
                .withQueryParam("count", equalTo("1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"market":"KRW-BTC","candle_date_time_utc":"2026-09-11T05:45:00",
                                  "opening_price":105231000.0,"high_price":105232000.0,"low_price":105231000.0,
                                  "trade_price":105232000.0}]
                                """)));

        List<Candle> candles = upbitPriceClient.getMinuteCandles("BTC", "KRW", 15, 1);

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).candleAt()).isEqualTo(LocalDateTime.of(2026, 9, 11, 5, 45).toInstant(ZoneOffset.UTC));
    }

    @Test
    void getWeekAndMonthCandlesMapOhlc() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/candles/weeks"))
                .withQueryParam("market", equalTo("KRW-BTC"))
                .withQueryParam("count", equalTo("1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"market":"KRW-BTC","candle_date_time_utc":"2026-09-07T00:00:00",
                                  "opening_price":109407000.0,"high_price":109556000.0,"low_price":104710000.0,
                                  "trade_price":105232000.0,"first_day_of_period":"2026-09-07"}]
                                """)));
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/candles/months"))
                .withQueryParam("market", equalTo("KRW-BTC"))
                .withQueryParam("count", equalTo("1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"market":"KRW-BTC","candle_date_time_utc":"2026-09-01T00:00:00",
                                  "opening_price":108304000.0,"high_price":112000000.0,"low_price":104710000.0,
                                  "trade_price":105232000.0,"first_day_of_period":"2026-09-01"}]
                                """)));

        List<Candle> weeks = upbitPriceClient.getWeekCandles("BTC", "KRW", 1);
        List<Candle> months = upbitPriceClient.getMonthCandles("BTC", "KRW", 1);

        assertThat(weeks.get(0).open()).isEqualByComparingTo("109407000.0");
        assertThat(months.get(0).open()).isEqualByComparingTo("108304000.0");
    }

    /**
     * 과거 페이징용 {@code to} 오버로드 — 실제 업비트 API가 요구하는 ISO8601 UTC 형식
     * ({@code yyyy-MM-dd'T'HH:mm:ss'Z'})으로 쿼리 파라미터에 실려 나가는지 검증한다
     * (2026-09-11 실측: 이 형식과 "yyyy-MM-dd HH:mm:ss" 둘 다 동일하게 동작하며, {@code to}는
     * exclusive 상한이다 — 해당 시각의 캔들 자체는 결과에서 제외됨).
     */
    @Test
    void getDayCandlesWithToPagesUsingIso8601UtcQueryParam() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/candles/days"))
                .withQueryParam("market", equalTo("KRW-BTC"))
                .withQueryParam("count", equalTo("1"))
                .withQueryParam("to", equalTo("2026-09-05T00:00:00Z"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"market":"KRW-BTC","candle_date_time_utc":"2026-09-04T00:00:00",
                                  "opening_price":110937000.0,"high_price":111392000.0,"low_price":107713000.0,
                                  "trade_price":109126000.0}]
                                """)));

        Instant to = LocalDateTime.of(2026, 9, 5, 0, 0).toInstant(ZoneOffset.UTC);
        List<Candle> candles = upbitPriceClient.getDayCandles("BTC", "KRW", 1, to);

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).candleAt()).isEqualTo(LocalDateTime.of(2026, 9, 4, 0, 0).toInstant(ZoneOffset.UTC));
    }

    /**
     * 존재하지 않는 마켓 코드는 {@code /v1/ticker}(200+빈 배열)와 달리 캔들 엔드포인트에서는
     * 정상 HTTP 404로 온다(2026-09-11 실측, {@code {"error":{"name":404,"message":"Code not found"}}}) —
     * 같은 업비트라도 엔드포인트별 매칭 실패 표현이 다르다는 점을 회귀 방지로 고정한다.
     */
    @Test
    void getDayCandlesThrowsTickerNotFoundOn404() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/candles/days"))
                .withQueryParam("market", equalTo("KRW-NOTEXIST"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"name\":404,\"message\":\"Code not found\"}}")));

        assertThatThrownBy(() -> upbitPriceClient.getDayCandles("NOTEXIST", "KRW", 1))
                .isInstanceOf(TickerNotFoundException.class);
    }

    @Test
    void getDayCandlesThrowsExternalPriceApiExceptionOnServerError() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/candles/days"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> upbitPriceClient.getDayCandles("BTC", "KRW", 1))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    @Test
    void getMinuteCandlesThrowsExternalPriceApiExceptionOnTimeoutTriggeringCircuitBreakerFallback() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/candles/minutes/1"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5000)));

        assertThatThrownBy(() -> upbitPriceClient.getMinuteCandles("BTC", "KRW", 1, 1))
                .isInstanceOf(ExternalPriceApiException.class);
    }
}
