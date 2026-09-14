package com.allfolio.infra.price;

import com.allfolio.domain.AssetType;
import com.allfolio.domain.Candle;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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

    /**
     * 분봉 조회(업비트 {@code GET /v1/candles/minutes/{unit}}). {@code unit}은 업비트가 지원하는
     * 값(1, 3, 5, 10, 15, 30, 60, 240)만 유효하며 그 외 값은 실제 호출 시 업비트가 400을 반환한다
     * (2026-09-11 실측, {@code {"error":{"name":400,"message":"specified unit is not valid."}}}) —
     * 이 클라이언트는 별도로 값을 검증하지 않고 업비트 응답을 그대로 신뢰한다(호출부가 고정된
     * 값만 넘기는 한 재현되지 않는 경로).
     */
    @CircuitBreaker(name = "upbit", fallbackMethod = "candlesFallback")
    public List<Candle> getMinuteCandles(String ticker, String currency, int unit, int count) {
        return fetchCandles("/v1/candles/minutes/" + unit, normalizeMarket(ticker, currency), count, null);
    }

    /**
     * 과거 페이징용 오버로드. {@code to}는 업비트 기준 exclusive 상한이다 — 2026-09-11 실측 결과
     * {@code to=2026-09-05T00:00:00Z}로 일봉을 조회하면 9/5 캔들 자체는 제외되고 9/4 캔들이 가장
     * 최근 결과로 온다("이 시각 이전 데이터"이지 "이 시각까지 포함"이 아님, 업비트 공식 문서와도
     * 일치). {@code null}이면 최신 캔들부터 반환한다.
     */
    @CircuitBreaker(name = "upbit", fallbackMethod = "candlesFallback")
    public List<Candle> getMinuteCandles(String ticker, String currency, int unit, int count, Instant to) {
        return fetchCandles("/v1/candles/minutes/" + unit, normalizeMarket(ticker, currency), count, to);
    }

    @CircuitBreaker(name = "upbit", fallbackMethod = "candlesFallback")
    public List<Candle> getDayCandles(String ticker, String currency, int count) {
        return fetchCandles("/v1/candles/days", normalizeMarket(ticker, currency), count, null);
    }

    @CircuitBreaker(name = "upbit", fallbackMethod = "candlesFallback")
    public List<Candle> getDayCandles(String ticker, String currency, int count, Instant to) {
        return fetchCandles("/v1/candles/days", normalizeMarket(ticker, currency), count, to);
    }

    @CircuitBreaker(name = "upbit", fallbackMethod = "candlesFallback")
    public List<Candle> getWeekCandles(String ticker, String currency, int count) {
        return fetchCandles("/v1/candles/weeks", normalizeMarket(ticker, currency), count, null);
    }

    @CircuitBreaker(name = "upbit", fallbackMethod = "candlesFallback")
    public List<Candle> getWeekCandles(String ticker, String currency, int count, Instant to) {
        return fetchCandles("/v1/candles/weeks", normalizeMarket(ticker, currency), count, to);
    }

    @CircuitBreaker(name = "upbit", fallbackMethod = "candlesFallback")
    public List<Candle> getMonthCandles(String ticker, String currency, int count) {
        return fetchCandles("/v1/candles/months", normalizeMarket(ticker, currency), count, null);
    }

    @CircuitBreaker(name = "upbit", fallbackMethod = "candlesFallback")
    public List<Candle> getMonthCandles(String ticker, String currency, int count, Instant to) {
        return fetchCandles("/v1/candles/months", normalizeMarket(ticker, currency), count, to);
    }

    /**
     * 업비트는 년봉 엔드포인트를 제공하지 않는다 — {@link #getMonthCandles}로 받은 월봉을 연 단위로
     * 직접 집계한다(순수 함수, 외부 의존 없음). 연도는 {@code candleAt}의 UTC 연도 기준으로 묶고,
     * open은 그 해에 실제로 주어진 월봉 중 가장 이른 달의 open, close는 가장 늦은 달의 close,
     * high/low는 그 구간의 max/min이다.
     *
     * <p><b>부분 연도(12개월 미만) 처리:</b> 어중간한 개수의 월봉(예: 3~11월치만 있는 경우)이 주어져도
     * 오류 없이 "주어진 달만으로" 집계한다 — 1월치가 없다고 open을 비워두거나 예외를 던지지 않는다.
     * 반환되는 각 연도 캔들의 {@code candleAt}은 실제 보유 데이터의 시작월이 아니라 해당 연도의
     * 1월 1일(UTC 00:00)로 고정한다 — "이 캔들이 몇 년도를 나타내는지"를 명확히 하기 위함이며,
     * 부분 데이터라고 해서 캔들이 나타내는 연도 자체가 달라지지는 않는다.</p>
     */
    public static List<Candle> aggregateYears(List<Candle> monthCandles) {
        return monthCandles.stream()
                .collect(Collectors.groupingBy(candle -> candle.candleAt().atZone(ZoneOffset.UTC).getYear()))
                .entrySet().stream()
                .map(entry -> aggregateYear(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(Candle::candleAt).reversed())
                .toList();
    }

    private static Candle aggregateYear(int year, List<Candle> monthsInYear) {
        List<Candle> sorted = monthsInYear.stream()
                .sorted(Comparator.comparing(Candle::candleAt))
                .toList();
        BigDecimal open = sorted.get(0).open();
        BigDecimal close = sorted.get(sorted.size() - 1).close();
        BigDecimal high = sorted.stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal low = sorted.stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow();
        Instant yearStart = LocalDateTime.of(year, 1, 1, 0, 0).toInstant(ZoneOffset.UTC);
        return new Candle(open, high, low, close, yearStart);
    }

    private List<Candle> fetchCandles(String path, String market, int count, Instant to) {
        List<UpbitCandleResponse> response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(path)
                        .queryParam("market", market)
                        .queryParam("count", count)
                        .queryParamIfPresent("to", Optional.ofNullable(to).map(DateTimeFormatter.ISO_INSTANT::format))
                        .build())
                .retrieve()
                // 존재하지 않는 마켓 코드는 /v1/ticker(200+빈 배열)와 달리 정상 HTTP 404로 온다
                // (2026-09-11 실측, {"error":{"name":404,"message":"Code not found"}}) — 같은
                // 업비트라도 엔드포인트별로 매칭 실패 표현이 다르므로 추측하지 말 것
                // (infra/price/CLAUDE.md 참고).
                .onStatus(status -> status.value() == 404, (req, res) -> {
                    throw new TickerNotFoundException("일치하는 마켓 코드를 찾을 수 없습니다: " + market);
                })
                .body(new ParameterizedTypeReference<>() {
                });

        if (response == null) {
            return List.of();
        }
        return response.stream().map(UpbitCandleResponse::toCandle).toList();
    }

    private List<Candle> candlesFallback(String ticker, String currency, int unit, int count, Throwable ex) {
        throw candlesFailure(ticker, ex);
    }

    private List<Candle> candlesFallback(String ticker, String currency, int unit, int count, Instant to, Throwable ex) {
        throw candlesFailure(ticker, ex);
    }

    private List<Candle> candlesFallback(String ticker, String currency, int count, Throwable ex) {
        throw candlesFailure(ticker, ex);
    }

    private List<Candle> candlesFallback(String ticker, String currency, int count, Instant to, Throwable ex) {
        throw candlesFailure(ticker, ex);
    }

    private RuntimeException candlesFailure(String ticker, Throwable ex) {
        if (ex instanceof TickerNotFoundException tickerNotFoundException) {
            return tickerNotFoundException;
        }
        return new ExternalPriceApiException("업비트 캔들 조회에 실패했습니다: " + ticker, ex);
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

    // candle_date_time_utc는 "2026-09-11T05:54:00"처럼 오프셋/Z 표기 없는 문자열이지만 실제로는
    // UTC 기준 캔들 구간 시작 시각이다(업비트 공식 문서·실측 공통 확인) — LocalDateTime으로 파싱한
    // 뒤 UTC 오프셋을 명시적으로 붙여 Instant로 변환한다.
    private record UpbitCandleResponse(
            @JsonProperty("candle_date_time_utc")
            String candleDateTimeUtc,
            @JsonProperty("opening_price")
            BigDecimal openingPrice,
            @JsonProperty("high_price")
            BigDecimal highPrice,
            @JsonProperty("low_price")
            BigDecimal lowPrice,
            @JsonProperty("trade_price")
            BigDecimal tradePrice
    ) {
        Candle toCandle() {
            return new Candle(openingPrice, highPrice, lowPrice, tradePrice,
                    LocalDateTime.parse(candleDateTimeUtc).toInstant(ZoneOffset.UTC));
        }
    }
}
