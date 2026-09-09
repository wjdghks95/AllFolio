package com.allfolio.infra.price;

import com.allfolio.domain.AssetType;
import com.allfolio.domain.Price;
import com.allfolio.domain.SearchResult;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.TickerNotFoundException;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Twelve Data(twelvedata.com) "/quote" 클라이언트 — 미국 주식(STOCK+USD) 시세 조회 전용
 * (docs/ROADMAP.md Task 025). 원화 환산은 이 클라이언트의 책임이 아니다 — {@code PriceService}가
 * {@code cachedUsdKrwRate()}로 환산까지 마친 뒤 반환한다(COIN·USDT에서 이미 겪은 결함 재발 방지).
 *
 * <p><b>실제 API 키로 curl 검증 완료</b>(2026-09-08):
 * <ul>
 *   <li>인증 실패(401)·잘못된 심볼(404)은 공공데이터포털과 달리 **정상 HTTP 상태 코드 자체가 4xx로
 *       온다**({@code {"code":404,"message":"...","status":"error"}} 형태) — Upbit(200+빈 배열) ·
 *       공공데이터포털(200+다른 루트 구조) 같은 "200이지만 실패" 함정이 이 API에는 없다. Spring
 *       {@code RestClient.retrieve()}의 기본 동작(4xx/5xx 시 예외)을 그대로 활용하면 되고, 404만
 *       {@link TickerNotFoundException}으로 구분하기 위해 {@code onStatus}를 추가했다.</li>
 *   <li>{@code close} 필드는 공공데이터포털과 마찬가지로 JSON에서 따옴표 붙은 문자열(예:
 *       {@code "319.97000"})로 오지만 이 프로젝트의 Jackson이 {@code BigDecimal} 필드로 그대로
 *       강제 변환해줘서 별도 처리가 필요 없다.</li>
 *   <li>휴장일(주말·공휴일) 직후에는 {@code close}가 마지막 정규장 종가를, {@code datetime}은
 *       시각 없이 날짜만({@code "2026-09-04"}) 반환한다 — 문서가 우려했던 "갱신 안 된 오래된
 *       datetime" 문제가 실제로 재현됐다. 반면 {@code last_quote_at}(Unix epoch, 초)은 그 거래일의
 *       실제 마지막 체결 시각(정규장 마감 무렵)을 초 단위로 담고 있어 더 정밀하고 파싱도 간단하다
 *       — {@link Price#asOf()}는 {@code Instant.now()}가 아니라 이 필드로 채운다(EOD 데이터인
 *       공공데이터포털과 달리 장중에는 실시간에 가깝게 갱신되지만, 휴장일에는 마지막 정규장
 *       마감 시각을 그대로 반영해 데이터가 오래됐다는 사실을 감추지 않는다).</li>
 * </ul>
 */
@Component
public class TwelveDataClient {

    private final RestClient restClient;

    public TwelveDataClient(RestClient.Builder restClientBuilder, TwelveDataProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl())
                // 쿼리 파라미터가 아닌 헤더로 전달 — URL 로그에 키가 남지 않는다(공식 문서 권장 방식).
                // 이 헤더 형식 자체로 실제 curl 호출 시 HTTP 200이 반환됨을 재검증했다(2026-09-09,
                // .claude/agents/twelvedata-api.md 「/quote 엔드포인트」 절 참고). ROADMAP Task 025
                // 본문의 "?symbol=&apikey=" 표기는 요청 개념을 보여준 예시일 뿐 쿼리 파라미터 강제가
                // 아니다 — Twelve Data는 헤더/쿼리 파라미터 둘 다 공식 지원한다.
                .defaultHeader("Authorization", "apikey " + properties.apiKey())
                .build();
    }

    @CircuitBreaker(name = "twelvedata", fallbackMethod = "fallback")
    public Price getPrice(String ticker) {
        TwelveDataQuoteResponse response = restClient.get()
                .uri("/quote?symbol={symbol}", ticker)
                .retrieve()
                // 존재하지 않는 심볼은 정상 HTTP 404로 온다(실측 확인) — 외부 서비스 장애가 아니라
                // 클라이언트 입력 오류이므로 TickerNotFoundException으로 구분한다(Circuit Breaker
                // ignore-exceptions 대상, application.yml 참고). 그 외 4xx/5xx는 RestClient 기본
                // 동작대로 예외를 던져 fallback에서 ExternalPriceApiException으로 변환된다.
                .onStatus(status -> status.value() == 404, (req, res) -> {
                    throw new TickerNotFoundException("일치하는 티커를 찾을 수 없습니다: " + ticker);
                })
                .body(TwelveDataQuoteResponse.class);

        if (response == null || response.close() == null) {
            throw new ExternalPriceApiException("미국 주식 시세 응답 형식이 올바르지 않습니다: " + ticker);
        }

        // 응답이 요청 심볼과 일치하는지 재검증한다(Upbit 사례처럼 API가 엉뚱한 심볼로 응답할
        // 가능성을 배제하지 않는다).
        if (!ticker.equals(response.symbol())) {
            throw new ExternalPriceApiException(
                    "요청한 티커(%s)와 응답 심볼(%s)이 일치하지 않습니다.".formatted(ticker, response.symbol()));
        }

        // AllFolio는 USD 주식에만 이 클라이언트를 쓰기로 확정돼 있다 — 응답이 예상과 다른 통화를
        // 반환하면(문서 밖 동작) 원화 환산을 담당하는 PriceService가 잘못된 통화를 그대로
        // 신뢰하지 않도록 여기서 방어적으로 걸러낸다.
        if (!"USD".equals(response.currency())) {
            throw new ExternalPriceApiException(
                    "예상치 못한 통화(%s)가 반환됐습니다: %s".formatted(response.currency(), ticker));
        }

        return new Price(response.close(), "USD", toAsOf(response.lastQuoteAt()));
    }

    /**
     * {@code last_quote_at}이 null인 경우를 대비한 방어적 폴백이다. 실측(2026-09-08, AAPL/MSFT
     * 정상 응답 기준)으로는 {@code close}가 채워진 응답에서 {@code last_quote_at}이 함께 null인
     * 사례를 아직 관찰하지 못했다 — 즉 이 분기가 실제로 실행되는 상황은 지금까지 재현된 적이 없다.
     *
     * <p>그럼에도 {@code Instant.now()}로 폴백하는 것은 {@code infra/price/CLAUDE.md}가 명시적으로
     * 금지한 패턴("Instant.now()를 쓰면 주말·공휴일에 오래된 종가를 '방금 갱신된 시세'처럼 보이게
     * 만든다")과 정확히 같은 문제를 안고 있다. 응답에 있는 {@code datetime}(날짜만, 시각 없음)을
     * 대신 폴백 후보로 검토했으나, 이 필드 자체가 실측 결과 정밀도가 낮다는 게 이미 확인돼 있어
     * (해당 날짜의 자정 등으로 임의 변환해야 하는데, 그 변환이 실제로 더 나은 근사인지 실측 없이는
     * 판단할 수 없다) 여기서 임의로 확정하지 않는다. 이 폴백이 실제로 실행되는 사례를 재현하기
     * 전까지는 {@code Instant.now()}를 잠정 유지하되, 이 코멘트로 "의도적으로 남겨둔 미해결
     * 함정"임을 명시해둔다 — 재현되면 {@code datetime} 기반 폴백으로 교체할지 재검토할 것.
     */
    private Instant toAsOf(Long lastQuoteAtEpochSeconds) {
        return lastQuoteAtEpochSeconds != null
                ? Instant.ofEpochSecond(lastQuoteAtEpochSeconds)
                : Instant.now();
    }

    private Price fallback(String ticker, Throwable ex) {
        if (ex instanceof TickerNotFoundException tickerNotFoundException) {
            throw tickerNotFoundException;
        }
        throw new ExternalPriceApiException("미국 주식 시세 조회에 실패했습니다: " + ticker, ex);
    }

    /**
     * 통합 종목 검색(GET /v1/assets/search)의 STOCK+USD 분기. 검색은 가격을 포함하지 않는다 —
     * 결과 목록에서 각 종목의 시세는 등록 후 {@link #getPrice}가 담당한다.
     *
     * <p><b>실제 API 키로 curl 검증 완료</b>(2026-09-09):
     * <ul>
     *   <li>결과 없음은 {@code /quote}의 404와 달리 **정상 HTTP 200 + {@code {"data":[],"status":"ok"}}**
     *       로 온다 — "결과 없음"이 빈 배열로 오는지 404로 오는지가 이 엔드포인트의 핵심 확인
     *       포인트였는데, 문서만으로는 알 수 없었고 실측으로 빈 배열임을 확정했다. 따라서
     *       {@code /quote}처럼 별도의 {@code onStatus(404, ...)}/{@link TickerNotFoundException}
     *       분기를 두지 않는다 — 이 메서드에서 그 조합은 재현되지 않는다.</li>
     *   <li>"AAPL" 검색 결과에는 미국 나스닥(NASDAQ, {@code instrument_type="Common Stock"},
     *       {@code currency="USD"}) 외에도 아르헨티나 CEDEAR·칠레 BVS·태국 SET 등 전세계 거래소의
     *       동일 티커 상장이 함께 섞여 온다({@code country}가 미국이 아니거나 {@code instrument_type}이
     *       "Depositary Receipt"/"Mutual Fund" 등 주식이 아닌 자산도 포함) — {@code instrument_type
     *       == "Common Stock"} && {@code currency == "USD"}로 걸러야 한다. 다만 이 두 조건만으로도
     *       칠레 BVS 상장(country="Chile", currency="USD")처럼 티커가 같은 복수 항목이 남는 경우가
     *       실측으로 확인됐다 — {@code symbol} 기준으로 첫 항목(API가 반환한 순서상 미국 거래소가
     *       먼저 옴)만 남기는 중복 제거를 추가했다(STOCK+KRW 분기의 {@code StockPriceClient.search}가
     *       이미 쓰는 것과 동일한 패턴).</li>
     *   <li>인증(apikey) 없이도 200이 반환된다 — {@code /symbol_search}는 공개 참조 데이터 엔드포인트로
     *       보인다(잘못된 키·키 자체를 생략해도 정상 검색 결과가 그대로 왔다). 그럼에도 401 등 4xx를
     *       받을 가능성 자체를 배제할 근거는 없어(문서상 공식 에러 코드 표에 401이 명시돼 있고, 무료
     *       한도 초과 시 429는 별도로 재현 가능성이 있음) 기존 {@code /quote}와 동일하게 4xx/5xx는
     *       {@link ExternalPriceApiException}으로 방어적으로 처리한다.</li>
     * </ul>
     */
    @CircuitBreaker(name = "twelvedata", fallbackMethod = "searchFallback")
    public List<SearchResult> search(String query) {
        TwelveDataSymbolSearchResponse response = restClient.get()
                .uri("/symbol_search?symbol={q}&outputsize=20", query)
                .retrieve()
                .body(TwelveDataSymbolSearchResponse.class);

        if (response == null || response.data() == null) {
            return List.of();
        }

        Map<String, SymbolSearchItem> deduped = new LinkedHashMap<>();
        for (SymbolSearchItem item : response.data()) {
            if ("Common Stock".equals(item.instrumentType()) && "USD".equals(item.currency())) {
                deduped.putIfAbsent(item.symbol(), item);
            }
        }

        return deduped.values().stream()
                .map(item -> new SearchResult(item.symbol(), item.instrumentName(), AssetType.STOCK, "USD"))
                .toList();
    }

    private List<SearchResult> searchFallback(String query, Throwable ex) {
        throw new ExternalPriceApiException("미국 주식 종목 검색에 실패했습니다: " + query, ex);
    }

    private record TwelveDataQuoteResponse(
            String symbol,
            String currency,
            BigDecimal close,
            @JsonProperty("last_quote_at")
            Long lastQuoteAt
    ) {
    }

    private record TwelveDataSymbolSearchResponse(List<SymbolSearchItem> data) {
    }

    private record SymbolSearchItem(
            String symbol,
            @JsonProperty("instrument_name")
            String instrumentName,
            @JsonProperty("instrument_type")
            String instrumentType,
            String country,
            String currency
    ) {
    }
}
