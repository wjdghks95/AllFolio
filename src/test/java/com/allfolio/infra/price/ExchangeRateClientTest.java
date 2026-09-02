package com.allfolio.infra.price;

import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.Price;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.github.tomakehurst.wiremock.WireMockServer;
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
    void resetStubs() {
        wireMockServer.resetAll();
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
}
