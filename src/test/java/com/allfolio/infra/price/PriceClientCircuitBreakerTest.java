package com.allfolio.infra.price;

import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.TickerNotFoundException;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/ROADMAP.md Task 021 — Circuit Breaker(반복 실패 시 호출을 잠시 차단하는 패턴)가 실제로
 * Open 상태로 전환되는지 검증한다. 정상/5xx/타임아웃은 UpbitPriceClientTest에서 이미 검증했으므로
 * 이 클래스는 CB Open 전환 하나에만 집중한다(중복 방지, CLAUDE.md Simplicity First).
 *
 * <p>Resilience4j 기본값(minimumNumberOfCalls=100)으로는 회로가 열리려면 100번 호출이 쌓여야 해
 * 테스트가 비현실적이다 — application.yml에 upbit 인스턴스를 slidingWindowSize=4로 명시 설정해뒀다.
 */
class PriceClientCircuitBreakerTest extends AbstractIntegrationTest {

    private static WireMockServer wireMockServer;
    private static WireMockServer stockWireMockServer;

    @Autowired
    private UpbitPriceClient upbitPriceClient;

    @Autowired
    private StockPriceClient stockPriceClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();
        stockWireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        stockWireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
        stockWireMockServer.stop();
    }

    @DynamicPropertySource
    static void upbitProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.upbit.base-url", () -> "http://localhost:" + wireMockServer.port());
        registry.add("allfolio.stock.base-url", () -> "http://localhost:" + stockWireMockServer.port());
    }

    @BeforeEach
    void resetStubsAndCircuitBreaker() {
        wireMockServer.resetAll();
        stockWireMockServer.resetAll();
        circuitBreakerRegistry.circuitBreaker("upbit").reset();
        circuitBreakerRegistry.circuitBreaker("stock").reset();
    }

    @Test
    void repeatedFailuresOpenCircuitAndSkipSubsequentCalls() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-BTC"))
                .willReturn(aResponse().withStatus(500)));

        // minimumNumberOfCalls(4)만큼 실제로 호출 — 전부 WireMock까지 요청이 간다.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> upbitPriceClient.getPrice("KRW-BTC"))
                    .isInstanceOf(ExternalPriceApiException.class);
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("upbit");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Open 상태에서의 5번째 호출도 예외는 나지만, 실제로는 WireMock에 요청이 가지 않아야 한다.
        assertThatThrownBy(() -> upbitPriceClient.getPrice("KRW-BTC"))
                .isInstanceOf(ExternalPriceApiException.class);

        wireMockServer.verify(4, getRequestedFor(urlEqualTo("/v1/ticker?markets=KRW-BTC")));
    }

    /**
     * 존재하지 않는 마켓 코드(정상 200 + 빈 배열)는 외부 서비스 장애가 아니라 클라이언트 입력 오류다.
     * application.yml의 upbit ignore-exceptions(TickerNotFoundException) 설정으로 CB 실패 집계에서
     * 제외되므로, minimumNumberOfCalls(4)를 넘겨 호출해도 CB는 계속 CLOSED여야 한다.
     */
    @Test
    void repeatedTickerNotFoundDoesNotOpenCircuit() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-NOPE"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> upbitPriceClient.getPrice("KRW-NOPE"))
                    .isInstanceOf(TickerNotFoundException.class);
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("upbit");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        wireMockServer.verify(6, getRequestedFor(urlEqualTo("/v1/ticker?markets=KRW-NOPE")));
    }

    /**
     * 요청 티커와 일치하는 종목코드가 없음(정상 200 응답, 매칭 실패)도 마찬가지로 클라이언트 입력
     * 오류이지 외부 서비스 장애가 아니다 — stock CB도 계속 CLOSED여야 한다.
     */
    @Test
    void repeatedStockTickerMismatchDoesNotOpenCircuit() {
        String requestPath = "/getStockPriceInfo?serviceKey=test-service-key&numOfRows=1&pageNo=1"
                + "&resultType=json&likeSrtnCd=999999";
        stockWireMockServer.stubFor(get(urlEqualTo(requestPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[]}}}}
                                """)));

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> stockPriceClient.getPrice("999999"))
                    .isInstanceOf(TickerNotFoundException.class);
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("stock");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        stockWireMockServer.verify(6, getRequestedFor(urlEqualTo(requestPath)));
    }
}
