package com.allfolio.infra.price;

import com.allfolio.domain.Price;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.TickerNotFoundException;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 업비트(COIN) 시세 조회 클라이언트. 장애 시 {@link #fallback}이 원인 예외를 보존한 채
 * {@link ExternalPriceApiException}으로 변환한다 — 캐시(Redis, 후속 Task)가 없어 직전 시세로
 * 대체 응답할 수 없다.
 */
@Component
public class UpbitPriceClient {

    private final RestClient restClient;

    public UpbitPriceClient(RestClient.Builder restClientBuilder, UpbitProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
    }

    @CircuitBreaker(name = "upbit", fallbackMethod = "fallback")
    public Price getPrice(String ticker) {
        List<UpbitTickerResponse> response = restClient.get()
                .uri("/v1/ticker?markets={ticker}", ticker)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        // 존재하지 않는 마켓 코드는 정상 200 응답 + 빈 배열로 온다 — 외부 서비스 장애가 아니라
        // 클라이언트 입력 오류이므로 TickerNotFoundException으로 구분한다
        // (Circuit Breaker ignore-exceptions 대상, application.yml 참고).
        if (response == null || response.isEmpty()) {
            throw new TickerNotFoundException("일치하는 마켓 코드를 찾을 수 없습니다: " + ticker);
        }

        BigDecimal tradePrice = response.get(0).tradePrice();
        return new Price(tradePrice, "KRW", Instant.now());
    }

    private Price fallback(String ticker, Throwable ex) {
        if (ex instanceof TickerNotFoundException tickerNotFoundException) {
            throw tickerNotFoundException;
        }
        throw new ExternalPriceApiException("업비트 시세 조회에 실패했습니다: " + ticker, ex);
    }

    private record UpbitTickerResponse(
            String market,
            @JsonProperty("trade_price")
            BigDecimal tradePrice
    ) {
    }
}
