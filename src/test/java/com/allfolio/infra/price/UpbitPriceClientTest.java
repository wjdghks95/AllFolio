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
}
