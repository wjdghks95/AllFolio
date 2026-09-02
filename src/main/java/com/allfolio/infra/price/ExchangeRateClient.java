package com.allfolio.infra.price;

import com.allfolio.domain.Price;
import com.allfolio.domain.exception.ExternalPriceApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Component
public class ExchangeRateClient {

    private final RestClient restClient;

    public ExchangeRateClient(RestClient.Builder restClientBuilder, ExchangeRateProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
    }

    @CircuitBreaker(name = "exchange-rate", fallbackMethod = "fallback")
    public Price getUsdKrwRate() {
        ExchangeRateResponse response = restClient.get()
                .uri("/v1/currencies/usd.json")
                .retrieve()
                .body(ExchangeRateResponse.class);

        BigDecimal krwRate = response.usd().get("krw");
        return new Price(krwRate, "KRW", Instant.now());
    }

    private Price fallback(Throwable ex) {
        throw new ExternalPriceApiException("환율 조회에 실패했습니다.", ex);
    }

    private record ExchangeRateResponse(
            String date,
            Map<String, BigDecimal> usd
    ) {
    }
}
