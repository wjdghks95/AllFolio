package com.allfolio.infra.price;

import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.Price;
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
}
