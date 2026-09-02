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
 * docs/ROADMAP.md Task 021 — 환율 시세 클라이언트. {@code @CircuitBreaker}는 Spring AOP 프록시를
 * 통해서만 동작하므로 직접 생성이 아닌 Spring 컨텍스트에서 주입받은 빈으로 검증한다.
 */
class ExchangeRateClientTest extends AbstractIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private ExchangeRateClient exchangeRateClient;

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
    static void exchangeRateProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.exchange-rate.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @BeforeEach
    void resetStubsAndCircuitBreaker() {
        wireMockServer.resetAll();
        circuitBreakerRegistry.circuitBreaker("exchange-rate").reset();
    }

    @Test
    void getUsdKrwRateMapsKrwFromUsdCurrencyMap() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/currencies/usd.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"date\":\"2026-08-31\",\"usd\":{\"krw\":1350.05,\"aed\":3.6725}}")));

        Price price = exchangeRateClient.getUsdKrwRate();

        assertThat(price.amount()).isEqualByComparingTo(new BigDecimal("1350.05"));
        assertThat(price.currency()).isEqualTo("KRW");
    }

    @Test
    void getUsdKrwRateThrowsExternalPriceApiExceptionOnServerError() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/currencies/usd.json"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> exchangeRateClient.getUsdKrwRate())
                .isInstanceOf(ExternalPriceApiException.class);
    }

    @Test
    void getUsdKrwRateThrowsExternalPriceApiExceptionOnTimeout() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/currencies/usd.json"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5000)));

        assertThatThrownBy(() -> exchangeRateClient.getUsdKrwRate())
                .isInstanceOf(ExternalPriceApiException.class);
    }

    /**
     * 정상 200 응답이지만 usd 맵에 krw 키가 없는 경우(응답 스키마 이상) — null을 그대로 반환하면
     * PriceService.getPrice()의 setScale() 호출에서 NPE로 이어지므로 여기서 명시적으로 막는다
     * (code-reviewer 지적, Task 021 후속).
     */
    @Test
    void getUsdKrwRateThrowsExternalPriceApiExceptionWhenKrwKeyMissing() {
        wireMockServer.stubFor(get(urlEqualTo("/v1/currencies/usd.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"date\":\"2026-08-31\",\"usd\":{\"aed\":3.6725}}")));

        assertThatThrownBy(() -> exchangeRateClient.getUsdKrwRate())
                .isInstanceOf(ExternalPriceApiException.class);
    }
}
