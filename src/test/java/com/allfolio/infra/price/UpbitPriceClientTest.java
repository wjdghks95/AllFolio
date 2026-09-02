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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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

        Price price = upbitPriceClient.getPrice("KRW-BTC");

        assertThat(price.amount()).isEqualByComparingTo(new BigDecimal("107747000.00000000"));
        assertThat(price.currency()).isEqualTo("KRW");
    }

    @Test
    void getPriceThrowsExternalPriceApiExceptionOnServerError() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-BTC"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> upbitPriceClient.getPrice("KRW-BTC"))
                .isInstanceOf(ExternalPriceApiException.class);
    }

    @Test
    void getPriceThrowsExternalPriceApiExceptionOnTimeout() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/ticker?markets=KRW-BTC"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5000)));

        assertThatThrownBy(() -> upbitPriceClient.getPrice("KRW-BTC"))
                .isInstanceOf(ExternalPriceApiException.class);
    }
}
