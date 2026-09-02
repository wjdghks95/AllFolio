package com.allfolio.infra.price;

import com.allfolio.domain.Price;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.TickerNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
 */
@Component
public class StockPriceClient {

    private static final DateTimeFormatter BAS_DT_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RestClient restClient;
    private final String serviceKey;

    public StockPriceClient(RestClient.Builder restClientBuilder, StockProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.serviceKey = properties.serviceKey();
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

    private record Item(String basDt, String srtnCd, BigDecimal clpr) {
    }
}
