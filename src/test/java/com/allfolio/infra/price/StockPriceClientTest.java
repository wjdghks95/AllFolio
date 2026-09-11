package com.allfolio.infra.price;

import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.DailyBar;
import com.allfolio.domain.Price;
import com.allfolio.domain.SearchResult;
import com.allfolio.domain.exception.ExternalPriceApiException;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/ROADMAP.md Task 021 — 국내 주식 시세 클라이언트(공공데이터포털 금융위원회_주식시세정보).
 * {@code @CircuitBreaker}는 Spring AOP 프록시를 통해서만 동작하므로 직접 생성이 아닌 Spring
 * 컨텍스트에서 주입받은 빈으로 검증한다.
 *
 * <p>WireMock 스텁 스키마는 실제 서비스키로 curl 검증을 마쳤다(2026-09-01): {@code items.item}은
 * 배열, {@code basDt} 생략 시 최신 거래일 데이터 반환, 숫자 필드(clpr 등)는 JSON에서 따옴표 붙은
 * 문자열로 옴 — 전부 이 스텁에 반영됨. 인증 실패는 정상 응답과 전혀 다른
 * {@code OpenAPI_ServiceResponse}/{@code cmmMsgHeader} 스키마로 온다는 것도 실제 호출로 확인함
 * ({@link #getPriceThrowsExternalPriceApiExceptionOnAuthError()}).
 */
class StockPriceClientTest extends AbstractIntegrationTest {

    private static final String TICKER = "005930";
    private static final String SERVICE_KEY = "test-service-key";
    private static final String REQUEST_PATH = "/getStockPriceInfo?serviceKey=" + SERVICE_KEY
            + "&numOfRows=1&pageNo=1&resultType=json&likeSrtnCd=" + TICKER;

    private static WireMockServer wireMockServer;

    @Autowired
    private StockPriceClient stockPriceClient;

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
    static void stockProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.stock.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @BeforeEach
    void resetStubsAndCircuitBreaker() {
        wireMockServer.resetAll();
        circuitBreakerRegistry.circuitBreaker("stock").reset();
    }

    @Test
    void getPriceMapsClprAndBasDtFromStockResponse() {
        wireMockServer.stubFor(get(urlEqualTo(REQUEST_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "response": {
                                    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                                    "body": {
                                      "numOfRows": 1, "pageNo": 1, "totalCount": 1,
                                      "items": {"item": [{
                                        "basDt": "20260830", "srtnCd": "005930", "isinCd": "KR7005930003",
                                        "itmsNm": "삼성전자", "mrktCtg": "KOSPI", "clpr": "71000"
                                      }]}
                                    }
                                  }
                                }
                                """)));

        Price price = stockPriceClient.getPrice(TICKER);

        assertThat(price.amount()).isEqualByComparingTo(new BigDecimal("71000"));
        assertThat(price.currency()).isEqualTo("KRW");
        assertThat(price.asOf()).isEqualTo(
                LocalDate.parse("20260830", DateTimeFormatter.BASIC_ISO_DATE)
                        .atStartOfDay(ZoneId.of("Asia/Seoul"))
                        .toInstant());
    }

    @Test
    void getPriceThrowsExternalPriceApiExceptionOnServerError() {
        wireMockServer.stubFor(get(urlEqualTo(REQUEST_PATH))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> stockPriceClient.getPrice(TICKER))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    @Test
    void getPriceThrowsExternalPriceApiExceptionOnTimeout() {
        wireMockServer.stubFor(get(urlEqualTo(REQUEST_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5000)));

        assertThatThrownBy(() -> stockPriceClient.getPrice(TICKER))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    /**
     * 실제로 유효하지 않은 서비스키로 호출해 확인한 진짜 응답(2026-09-01):
     * {@code {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
     * "returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}} — 정상 응답의
     * {@code response.header.resultCode} 스키마와 전혀 다른 루트 구조라, StockPriceApiResponse가
     * response 필드를 못 채워 null이 되고 방어 로직이 ExternalPriceApiException으로 전환한다.
     */
    @Test
    void getPriceThrowsExternalPriceApiExceptionOnAuthError() {
        wireMockServer.stubFor(get(urlEqualTo(REQUEST_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "OpenAPI_ServiceResponse": {
                                    "cmmMsgHeader": {
                                      "errMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
                                      "returnAuthMsg": "등록되지 않은 서비스키",
                                      "returnReasonCode": "30"
                                    }
                                  }
                                }
                                """)));

        assertThatThrownBy(() -> stockPriceClient.getPrice(TICKER))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    @Test
    void searchUsesLikeItmsNmForNonNumericQuery() {
        String requestPath = "/getStockPriceInfo?serviceKey=" + SERVICE_KEY
                + "&numOfRows=20&pageNo=1&resultType=json&likeItmsNm=%EC%82%BC%EC%84%B1%EC%A0%84%EC%9E%90";
        wireMockServer.stubFor(get(urlEqualTo(requestPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "response": {
                                    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                                    "body": {
                                      "numOfRows": 20, "pageNo": 1, "totalCount": 2,
                                      "items": {"item": [
                                        {"basDt": "20260908", "srtnCd": "005930", "itmsNm": "삼성전자", "clpr": "269500"},
                                        {"basDt": "20260908", "srtnCd": "005935", "itmsNm": "삼성전자우", "clpr": "198400"}
                                      ]}
                                    }
                                  }
                                }
                                """)));

        List<SearchResult> results = stockPriceClient.search("삼성전자");

        assertThat(results).containsExactly(
                new SearchResult("005930", "삼성전자", AssetType.STOCK, "KRW"),
                new SearchResult("005935", "삼성전자우", AssetType.STOCK, "KRW"));
    }

    @Test
    void searchUsesLikeSrtnCdForNumericQueryAndDedupesDuplicateTickerAcrossDates() {
        String requestPath = "/getStockPriceInfo?serviceKey=" + SERVICE_KEY
                + "&numOfRows=20&pageNo=1&resultType=json&likeSrtnCd=" + TICKER;
        // 2026-09-09 실측: basDt를 지정하지 않으면 동일 종목의 여러 거래일 데이터가 최신순으로 온다
        // (하나의 종목코드만 매칭돼도 numOfRows 잔여분이 과거 날짜로 채워짐) — 클라이언트가 srtnCd
        // 기준으로 첫 번째(가장 최근) 항목만 남기는지 검증한다.
        wireMockServer.stubFor(get(urlEqualTo(requestPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "response": {
                                    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                                    "body": {
                                      "numOfRows": 20, "pageNo": 1, "totalCount": 2,
                                      "items": {"item": [
                                        {"basDt": "20260908", "srtnCd": "005930", "itmsNm": "삼성전자", "clpr": "269500"},
                                        {"basDt": "20260907", "srtnCd": "005930", "itmsNm": "삼성전자", "clpr": "270000"}
                                      ]}
                                    }
                                  }
                                }
                                """)));

        List<SearchResult> results = stockPriceClient.search(TICKER);

        assertThat(results).containsExactly(new SearchResult("005930", "삼성전자", AssetType.STOCK, "KRW"));
    }

    @Test
    void searchReturnsEmptyListWhenNoItemsMatch() {
        String requestPath = "/getStockPriceInfo?serviceKey=" + SERVICE_KEY
                + "&numOfRows=20&pageNo=1&resultType=json&likeItmsNm=%EC%A1%B4%EC%9E%AC%ED%95%98%EC%A7%80%EC%95%8A%EC%9D%8C";
        wireMockServer.stubFor(get(urlEqualTo(requestPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "response": {
                                    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                                    "body": {
                                      "numOfRows": 20, "pageNo": 1, "totalCount": 0,
                                      "items": {"item": []}
                                    }
                                  }
                                }
                                """)));

        List<SearchResult> results = stockPriceClient.search("존재하지않음");

        assertThat(results).isEmpty();
    }

    @Test
    void searchThrowsExternalPriceApiExceptionOnAuthError() {
        String requestPath = "/getStockPriceInfo?serviceKey=" + SERVICE_KEY
                + "&numOfRows=20&pageNo=1&resultType=json&likeItmsNm=%EC%82%BC%EC%84%B1%EC%A0%84%EC%9E%90";
        wireMockServer.stubFor(get(urlEqualTo(requestPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "OpenAPI_ServiceResponse": {
                                    "cmmMsgHeader": {
                                      "errMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
                                      "returnAuthMsg": "등록되지 않은 서비스키",
                                      "returnReasonCode": "30"
                                    }
                                  }
                                }
                                """)));

        assertThatThrownBy(() -> stockPriceClient.search("삼성전자"))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    /**
     * beginBasDt/endBasDt 범위 필터·OHLC 필드(mkp/hipr/lopr/clpr) 매핑을 실제 서비스키 curl
     * 검증(2026-09-11)에 맞춰 재현한다. 응답은 basDt 내림차순(최신 우선)으로 오지만,
     * {@link StockPriceClient#getDailySeries}는 캔들 차트 소비를 배려해 오름차순으로 뒤집는다.
     */
    @Test
    void getDailySeriesMapsOhlcAndSortsAscendingByDate() {
        String requestPath = "/getStockPriceInfo?serviceKey=" + SERVICE_KEY
                + "&numOfRows=10&pageNo=1&resultType=json&likeSrtnCd=" + TICKER
                + "&beginBasDt=20260901&endBasDt=20260910";
        wireMockServer.stubFor(get(urlEqualTo(requestPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "response": {
                                    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                                    "body": {
                                      "numOfRows": 10, "pageNo": 1, "totalCount": 2,
                                      "items": {"item": [
                                        {"basDt": "20260902", "srtnCd": "005930", "itmsNm": "삼성전자",
                                         "mkp": "252000", "hipr": "255500", "lopr": "249500", "clpr": "250500"},
                                        {"basDt": "20260901", "srtnCd": "005930", "itmsNm": "삼성전자",
                                         "mkp": "256500", "hipr": "262500", "lopr": "254000", "clpr": "261000"}
                                      ]}
                                    }
                                  }
                                }
                                """)));

        List<DailyBar> bars = stockPriceClient.getDailySeries(
                TICKER, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10));

        assertThat(bars).containsExactly(
                new DailyBar(LocalDate.of(2026, 9, 1), new BigDecimal("256500"), new BigDecimal("262500"),
                        new BigDecimal("254000"), new BigDecimal("261000")),
                new DailyBar(LocalDate.of(2026, 9, 2), new BigDecimal("252000"), new BigDecimal("255500"),
                        new BigDecimal("249500"), new BigDecimal("250500")));
    }

    /**
     * "해당 티커 없음"과 "그 범위에 거래일 데이터 없음"을 API 응답만으로 구분할 수 없다(둘 다 200 +
     * 빈 배열, 2026-09-11 curl 실측). getPrice와 달리 TickerNotFoundException을 던지지 않고
     * search와 동일하게 빈 리스트를 반환한다.
     */
    @Test
    void getDailySeriesReturnsEmptyListWhenNoItemsMatch() {
        String requestPath = "/getStockPriceInfo?serviceKey=" + SERVICE_KEY
                + "&numOfRows=10&pageNo=1&resultType=json&likeSrtnCd=" + TICKER
                + "&beginBasDt=20261001&endBasDt=20261010";
        wireMockServer.stubFor(get(urlEqualTo(requestPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "response": {
                                    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                                    "body": {
                                      "numOfRows": 10, "pageNo": 1, "totalCount": 0,
                                      "items": {"item": []}
                                    }
                                  }
                                }
                                """)));

        List<DailyBar> bars = stockPriceClient.getDailySeries(
                TICKER, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10));

        assertThat(bars).isEmpty();
    }

    @Test
    void getDailySeriesThrowsExternalPriceApiExceptionOnAuthError() {
        String requestPath = "/getStockPriceInfo?serviceKey=" + SERVICE_KEY
                + "&numOfRows=10&pageNo=1&resultType=json&likeSrtnCd=" + TICKER
                + "&beginBasDt=20260901&endBasDt=20260910";
        wireMockServer.stubFor(get(urlEqualTo(requestPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "OpenAPI_ServiceResponse": {
                                    "cmmMsgHeader": {
                                      "errMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
                                      "returnAuthMsg": "등록되지 않은 서비스키",
                                      "returnReasonCode": "30"
                                    }
                                  }
                                }
                                """)));

        assertThatThrownBy(() -> stockPriceClient.getDailySeries(
                TICKER, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10)))
                .isInstanceOf(ExternalPriceApiException.class);
    }
}
