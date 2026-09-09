package com.allfolio.infra.price;

import com.allfolio.domain.AssetType;
import com.allfolio.domain.Price;
import com.allfolio.domain.SearchResult;
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
    public Price getPrice(String ticker, String currency) {
        String market = normalizeMarket(ticker, currency);
        List<UpbitTickerResponse> response = restClient.get()
                .uri("/v1/ticker?markets={market}", market)
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
        // 응답 통화는 넘겨받은 currency 인자가 아니라 실제로 조회한 마켓 접두어에서 도출한다.
        // 이미 마켓 접두어가 포함된 티커(과거 데이터·직접 입력)는 자산의 currency 필드와
        // 어긋날 수 있는데(예: ticker="KRW-BTC"인데 currency="USD"로 등록), currency 인자를
        // 그대로 믿으면 원화 시세에 환율을 또 곱하거나 달러 시세를 원화로 잘못 표기하게 된다
        // (code-reviewer M1 지적, 최대 약 1,300배 오차 실측).
        return new Price(tradePrice, domainCurrencyOf(market), Instant.now());
    }

    // 사용자는 자산 등록 화면에서 코인 심볼만 입력한다("BTC") — 업비트 API가 요구하는
    // "KRW-BTC" 마켓 코드 자체는 자산의 통화(currency)로부터 붙여준다. 이미 마켓 접두어가
    // 포함된 티커(과거 데이터·직접 입력)는 그대로 존중해 이중으로 붙이지 않는다.
    private String normalizeMarket(String ticker, String currency) {
        return ticker.contains("-") ? ticker : upbitQuoteCurrency(currency) + "-" + ticker;
    }

    // 업비트는 "USD-BTC" 같은 달러 마켓을 지원하지 않는다 — 원화(KRW) 마켓과 스테이블코인
    // (USDT) 마켓만 있다. 자산 통화가 USD면 사실상 동일 가치인 USDT 마켓으로 대신 조회한다.
    // KRW/USD 외 통화(예: JPY — CreateAssetRequest의 currency 정규식은 임의의 3글자 통화를
    // 허용한다)는 그대로 접두어로 써 "JPY-BTC"를 조회하는데, 업비트에 그런 마켓이 없어
    // TickerNotFoundException으로 안전하게 실패한다(성공 응답을 조작해서 만들지 않는 한
    // coinPrice()까지 도달하지 않는다) — 새 통화를 지원하려면 여기부터 고쳐야 한다.
    private String upbitQuoteCurrency(String currency) {
        return "USD".equals(currency) ? "USDT" : currency;
    }

    /**
     * 통합 종목 검색(GET /v1/assets/search, COIN 분기)용 업비트 전체 마켓 목록 조회. 특정 티커가
     * 아니라 {@code /v1/market/all}로 전체 마켓 코드를 받아온다는 점이 {@link #getPrice}와 다르다.
     * AllFolio가 지원하는 통화(KRW/USD)에 대응하는 KRW-, USDT- 접두어 마켓만 남기고, 그 외 코인마켓
     * (BTC- 접두어 등)은 제외한다.
     */
    @CircuitBreaker(name = "upbit", fallbackMethod = "listMarketsFallback")
    public List<SearchResult> listMarkets() {
        List<UpbitMarketResponse> response = restClient.get()
                .uri("/v1/market/all")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (response == null) {
            return List.of();
        }

        return response.stream()
                .filter(market -> market.market().startsWith("KRW-") || market.market().startsWith("USDT-"))
                .map(market -> new SearchResult(
                        market.market().substring(market.market().indexOf('-') + 1),
                        market.koreanName(),
                        AssetType.COIN,
                        domainCurrencyOf(market.market())))
                .toList();
    }

    // 마켓 접두어(KRW-BTC의 "KRW")를 도메인 통화 개념으로 되돌린다 — USDT는 업비트 표기일 뿐
    // 자산 통화로는 "USD"로 부른다(PrecisionScale 등 나머지 코드는 USDT를 모른다).
    private String domainCurrencyOf(String market) {
        String quotePrefix = market.substring(0, market.indexOf('-'));
        return "USDT".equals(quotePrefix) ? "USD" : quotePrefix;
    }

    private Price fallback(String ticker, String currency, Throwable ex) {
        if (ex instanceof TickerNotFoundException tickerNotFoundException) {
            throw tickerNotFoundException;
        }
        throw new ExternalPriceApiException("업비트 시세 조회에 실패했습니다: " + ticker, ex);
    }

    private List<SearchResult> listMarketsFallback(Throwable ex) {
        throw new ExternalPriceApiException("업비트 마켓 목록 조회에 실패했습니다", ex);
    }

    private record UpbitTickerResponse(
            String market,
            @JsonProperty("trade_price")
            BigDecimal tradePrice
    ) {
    }

    private record UpbitMarketResponse(
            String market,
            @JsonProperty("korean_name")
            String koreanName
    ) {
    }
}
