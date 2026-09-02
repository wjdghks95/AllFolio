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

        // usd 맵에 krw 키가 없으면(응답 스키마가 깨진 경우) null이 그대로 Price에 담겨 이후
        // PriceService.getPrice()의 setScale() 호출에서 NPE가 난다. 이 API는 클라이언트가 값을
        // 넘기지 않는 고정 엔드포인트라(UpbitPriceClient/StockPriceClient의 TickerNotFoundException과
        // 달리) 매칭 실패가 클라이언트 입력 오류일 수 없다 — 응답 형식 이상이므로
        // ExternalPriceApiException으로 취급해 Circuit Breaker 실패 집계에 그대로 반영한다.
        if (response == null || response.usd() == null || response.usd().get("krw") == null) {
            throw new ExternalPriceApiException("환율 응답 형식이 올바르지 않습니다.");
        }

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
