package com.allfolio.infra.price;

import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.Price;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application.yml}의 실제 {@code allfolio.stock.base-url}은 경로 세그먼트를 포함한다
 * ({@code https://apis.data.go.kr/1160100/GetStockSecuritiesInfoService_V2}). 그런데
 * {@link StockPriceClientTest} 등 다른 모든 테스트는 base-url을 경로 세그먼트 없는
 * {@code http://localhost:{port}}로 덮어써서, "base-url(경로 세그먼트 포함) + 각 메서드의 상대
 * 경로({@code /getStockPriceInfo_V2})를 합쳤을 때 실제 V2 전체 경로가 만들어지는가"를 검증하는
 * 테스트가 하나도 없었다(code-reviewer 지적, V2 전환 리뷰 2026-09-22). 이 테스트는 그 공백을 메운다.
 *
 * <p>다른 메서드({@code search}/{@code getDailySeries})도 같은 {@code restClient} 인스턴스(같은
 * base-url)를 공유하므로, 경로 조합 매커니즘 자체는 {@link #getPrice} 기준 이 테스트 하나로 충분하다.
 */
class StockPriceClientBaseUrlIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE_PATH = "/1160100/GetStockSecuritiesInfoService_V2";

    private static WireMockServer wireMockServer;

    @Autowired
    private StockPriceClient stockPriceClient;

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
        registry.add("allfolio.stock.base-url",
                () -> "http://localhost:" + wireMockServer.port() + BASE_PATH);
    }

    @Test
    void getPriceRequestsRealV2PathComposedFromBaseUrlAndOperationPath() {
        wireMockServer.stubFor(get(urlPathEqualTo(BASE_PATH + "/getStockPriceInfo_V2"))
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

        Price price = stockPriceClient.getPrice("005930");

        assertThat(price.amount()).isEqualByComparingTo(new BigDecimal("71000"));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(BASE_PATH + "/getStockPriceInfo_V2")));
    }
}
