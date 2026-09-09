package com.allfolio.infra.price;

import com.allfolio.domain.AssetType;
import com.allfolio.domain.Price;
import com.allfolio.domain.SearchResult;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.TickerNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 공공데이터포털 "금융위원회_주식시세정보"(getStockSecuritiesInfoService) 클라이언트.
 * 이 API는 실시간이 아닌 전일 종가(EOD, 일 1회 갱신) 데이터이므로 {@link Price#asOf()}는
 * 조회 시각({@code Instant.now()})이 아닌 응답의 {@code basDt}(기준일자)를 반영한다
 * (.claude/agents/stock-price-api.md 참고).
 *
 * <p><b>실제 서비스키로 검증 완료</b>(2026-09-01, curl 직접 호출):
 * <ul>
 *   <li>{@code items.item}은 배열이 맞다({@code numOfRows=1}이어도 원소 1개짜리 배열).</li>
 *   <li>{@code basDt}를 지정하지 않으면 최신 거래일 데이터가 온다.</li>
 *   <li>{@code clpr} 등 숫자 필드는 JSON에서 따옴표 붙은 문자열("260000")로 온다 — 이 프로젝트의
 *       Jackson이 {@code BigDecimal} 필드로 문자열 토큰을 그대로 강제 변환해줘서 별도 처리 없이
 *       정상 매핑됨을 확인했다.</li>
 *   <li>인증 실패(예: 등록되지 않은 서비스키)는 정상 응답과 전혀 다른 루트 구조
 *       ({@code {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{...}}}})로 온다 — {@code response}
 *       필드가 채워지지 않아 null이 되므로 아래 방어 로직이 그대로 {@link ExternalPriceApiException}으로
 *       전환한다(별도 파싱 분기 불필요).</li>
 * </ul>
 *
 * <p><b>{@link #search}(Task 026) 실제 서비스키로 검증 완료</b>(2026-09-09, curl 직접 호출):
 * {@code basDt}를 지정하지 않으면 응답이 최근 거래일부터 과거 순으로 정렬되어, 매칭 종목 수가 적은
 * 질의어일수록 {@code numOfRows} 잔여분이 같은 종목의 과거 날짜 데이터로 채워진다(예: "005930"으로
 * 검색 시 종목 1개인데도 서로 다른 20개 날짜가 옴). {@code srtnCd} 기준 첫 항목(=최근 날짜)만
 * 남기는 클라이언트 측 중복 제거로 해결했다. 자세한 내용은 .claude/agents/stock-price-api.md의
 * "Task 026" 절 참고.
 */
@Component
public class StockPriceClient {

    private static final DateTimeFormatter BAS_DT_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Pattern PERCENT_ENCODED = Pattern.compile("%[0-9A-Fa-f]{2}");

    private final RestClient restClient;
    private final String serviceKey;

    public StockPriceClient(RestClient.Builder restClientBuilder, StockProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.serviceKey = decodeIfAlreadyEncoded(properties.serviceKey());
    }

    // 공공데이터포털은 발급 키를 "인코딩 키"(이미 URL-인코딩됨, 예: "=="가 "%3D%3D")와 "디코딩 키"
    // (원문 그대로, Base64라 리터럴 "+"/"="를 포함할 수 있음) 두 형식으로 나눠 제공한다. RestClient의
    // {serviceKey} 템플릿 변수는 넘겨받은 값을 "아직 인코딩 안 된 원문"으로 보고 자체적으로 한 번
    // 인코딩하므로, 두 형식을 구분 없이 그대로 넘기면 인코딩 키는 이중 인코딩("등록되지 않은
    // 서비스키" 인증 실패, curl 직접 호출로 재현 확인)되고, 반대로 디코딩 키를 여기서 무조건
    // URLDecoder로 한 번 더 돌리면 리터럴 "+"가 공백으로 바뀌어 역시 깨진다.
    //
    // Base64 알파벳(A-Z a-z 0-9 + / =)에는 "%"가 절대 나오지 않으므로, "%XX" 패턴이 있으면 그 자체가
    // "이미 인코딩된 키"라는 확실한 신호다 — 그때만 디코딩해서 원문으로 되돌리고, 아니면 원문(디코딩
    // 키 또는 특수문자 없는 평문 키) 그대로 둬 RestClient가 처음이자 유일하게 인코딩하게 한다.
    static String decodeIfAlreadyEncoded(String serviceKey) {
        String safeServiceKey = Objects.requireNonNullElse(serviceKey, "");
        return PERCENT_ENCODED.matcher(safeServiceKey).find()
                ? URLDecoder.decode(safeServiceKey, StandardCharsets.UTF_8)
                : safeServiceKey;
    }

    @CircuitBreaker(name = "stock", fallbackMethod = "fallback")
    public Price getPrice(String ticker) {
        StockPriceApiResponse response = restClient.get()
                .uri("/getStockPriceInfo?serviceKey={serviceKey}&numOfRows=1&pageNo=1&resultType=json&likeSrtnCd={ticker}",
                        serviceKey, ticker)
                .retrieve()
                .body(StockPriceApiResponse.class);

        Item item = extractMatchingItem(response, ticker);
        return new Price(item.clpr(), "KRW", toAsOf(item.basDt()));
    }

    private Item extractMatchingItem(StockPriceApiResponse response, String ticker) {
        if (response == null || response.response() == null
                || response.response().body() == null
                || response.response().body().items() == null
                || response.response().body().items().item() == null) {
            throw new ExternalPriceApiException("주식 시세 응답 형식이 올바르지 않습니다: " + ticker);
        }

        Header header = response.response().header();
        if (header != null && header.resultCode() != null && !"00".equals(header.resultCode())) {
            throw new ExternalPriceApiException(
                    "주식 시세 조회 실패(%s): %s".formatted(header.resultCode(), header.resultMsg()));
        }

        // likeSrtnCd는 포함 검색이라(정확 일치 파라미터 없음) 요청한 티커와 정확히 일치하는 항목만 사용한다.
        // 정상 200 응답에서 매칭 실패는 외부 서비스 장애가 아니라 클라이언트 입력 오류이므로
        // TickerNotFoundException으로 구분한다(Circuit Breaker ignore-exceptions 대상, application.yml 참고).
        return response.response().body().items().item().stream()
                .filter(item -> ticker.equals(item.srtnCd()))
                .findFirst()
                .orElseThrow(() -> new TickerNotFoundException("일치하는 종목코드를 찾을 수 없습니다: " + ticker));
    }

    private Instant toAsOf(String basDt) {
        return LocalDate.parse(basDt, BAS_DT_FORMAT).atStartOfDay(KST).toInstant();
    }

    private Price fallback(String ticker, Throwable ex) {
        if (ex instanceof TickerNotFoundException tickerNotFoundException) {
            throw tickerNotFoundException;
        }
        throw new ExternalPriceApiException("주식 시세 조회에 실패했습니다: " + ticker, ex);
    }

    /**
     * 통합 종목 검색(GET /v1/assets/search)의 STOCK+KRW 분기. 질의어가 숫자로만 구성되면 종목코드
     * 포함검색({@code likeSrtnCd}), 아니면 종목명 포함검색({@code likeItmsNm})으로 라우팅한다.
     * 검색은 가격을 포함하지 않는다 — 결과 목록에서 각 종목의 시세는 등록 후 {@link #getPrice}가 담당한다.
     */
    @CircuitBreaker(name = "stock", fallbackMethod = "searchFallback")
    public List<SearchResult> search(String query) {
        boolean numeric = query.chars().allMatch(Character::isDigit);
        String param = numeric ? "likeSrtnCd" : "likeItmsNm";
        StockPriceApiResponse response = restClient.get()
                .uri("/getStockPriceInfo?serviceKey={serviceKey}&numOfRows=20&pageNo=1&resultType=json&"
                        + param + "={query}", serviceKey, query)
                .retrieve()
                .body(StockPriceApiResponse.class);

        return extractSearchResults(response, query);
    }

    private List<SearchResult> extractSearchResults(StockPriceApiResponse response, String query) {
        if (response == null || response.response() == null
                || response.response().body() == null
                || response.response().body().items() == null
                || response.response().body().items().item() == null) {
            throw new ExternalPriceApiException("주식 종목 검색 응답 형식이 올바르지 않습니다: " + query);
        }

        Header header = response.response().header();
        if (header != null && header.resultCode() != null && !"00".equals(header.resultCode())) {
            throw new ExternalPriceApiException(
                    "주식 종목 검색 실패(%s): %s".formatted(header.resultCode(), header.resultMsg()));
        }

        // basDt를 지정하지 않으면 최근 거래일부터 과거 순으로 정렬된 여러 날짜의 데이터가 함께 온다
        // (2026-09-09 curl 실측, .claude/agents/stock-price-api.md Task 026 절 참고) — 매칭되는
        // 종목 수가 적은 질의어일수록 numOfRows 잔여분이 같은 종목의 과거 날짜로 채워져 동일 종목이
        // 중복 노출된다. srtnCd 기준으로 첫 번째(가장 최근 날짜) 항목만 남겨 중복을 제거한다.
        Map<String, Item> deduped = new LinkedHashMap<>();
        for (Item item : response.response().body().items().item()) {
            deduped.putIfAbsent(item.srtnCd(), item);
        }

        return deduped.values().stream()
                .map(item -> new SearchResult(item.srtnCd(), item.itmsNm(), AssetType.STOCK, "KRW"))
                .toList();
    }

    private List<SearchResult> searchFallback(String query, Throwable ex) {
        throw new ExternalPriceApiException("주식 종목 검색에 실패했습니다: " + query, ex);
    }

    private record StockPriceApiResponse(Response response) {
    }

    private record Response(Header header, Body body) {
    }

    private record Header(String resultCode, String resultMsg) {
    }

    private record Body(Items items) {
    }

    private record Items(List<Item> item) {
    }

    private record Item(String basDt, String srtnCd, String itmsNm, BigDecimal clpr) {
    }
}
