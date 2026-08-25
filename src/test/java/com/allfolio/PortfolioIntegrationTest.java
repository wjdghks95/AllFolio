package com.allfolio;

import com.allfolio.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.ObjectMapper;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 013 — GET /v1/portfolio 포트폴리오 홈 API 통합 검증.
 * AssetIntegrationTest의 MockMvcTester 컨벤션(post/authorizedXxx 헬퍼, bodyOf)을
 * 그대로 따르되, 저장소 컨벤션(각 통합 테스트가 헬퍼를 독립 보유)에 맞춰 복붙한다.
 */
@AutoConfigureMockMvc
class PortfolioIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        tokenA = accessTokenOf(signup("trader-a@example.com", "correct-horse-battery"));
        tokenB = accessTokenOf(signup("trader-b@example.com", "correct-horse-battery"));
    }

    @Test
    void portfolioWithNoAssetsReturnsEmptyItemsAndEmptyTotals() {
        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        assertThat(itemsOf(body)).isEmpty();
        assertThat(totalCostByCurrencyOf(body)).isEmpty();
    }

    @Test
    void portfolioAggregatesCostAcrossAssetTypesAndCurrencies() {
        createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000"));
        createAsset(tokenA, stockRequest("005930", "삼성전자", "3", "58500"));
        createAsset(tokenA, coinRequest("BTC", "비트코인", "0.05123456", "82000000"));
        createAsset(tokenA, cashRequest("KRW", "원화 예수금", "1500000", "1"));
        createAsset(tokenA, stockRequestWithCurrency("AAPL", "Apple Inc.", "12", "182.5000", "USD"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        List<Map<String, Object>> items = itemsOf(body);
        assertThat(items).hasSize(5);

        // quantity는 자산유형·통화 무관 항상 8자리, avgPrice는 cost와 동일한 스케일 규칙(COIN 8자리,
        // 그 외 통화 기준)을 따른다 — code-reviewer 2차 검증이 이 필드들의 검증 공백을 지적해 추가.
        Map<String, Object> samsung1 = itemByTickerAndCost(items, "005930", "600000");
        assertThat(samsung1.get("cost")).isEqualTo("600000");
        assertThat(samsung1.get("quantity")).isEqualTo("10.00000000");
        assertThat(samsung1.get("avgPrice")).isEqualTo("60000");
        Map<String, Object> samsung2 = itemByTickerAndCost(items, "005930", "175500");
        assertThat(samsung2.get("cost")).isEqualTo("175500");
        Map<String, Object> btc = itemByTicker(items, "BTC");
        assertThat(btc.get("cost")).isEqualTo("4201233.92000000");
        assertThat(btc.get("quantity")).isEqualTo("0.05123456");
        assertThat(btc.get("avgPrice")).isEqualTo("82000000.00000000");
        Map<String, Object> cash = itemByTicker(items, "KRW");
        assertThat(cash.get("cost")).isEqualTo("1500000");
        assertThat(cash.get("quantity")).isEqualTo("1500000.00000000");
        assertThat(cash.get("avgPrice")).isEqualTo("1");
        Map<String, Object> aapl = itemByTicker(items, "AAPL");
        assertThat(aapl.get("cost")).isEqualTo("2190.0000");
        assertThat(aapl.get("quantity")).isEqualTo("12.00000000");
        assertThat(aapl.get("avgPrice")).isEqualTo("182.5000");

        // 통화별 합계는 스케일이 큰 COIN 원가(4201233.92000000)가 섞여도 통화 스케일(KRW 0자리)로
        // 반올림된다 — 600000+175500+4201233.92+1500000=6476733.92 → HALF_UP, scale 0 → 6476734.
        Map<String, String> totalCostByCurrency = totalCostByCurrencyOf(body);
        assertThat(totalCostByCurrency.get("KRW")).isEqualTo("6476734");
        assertThat(totalCostByCurrency.get("USD")).isEqualTo("2190.0000");
    }

    /**
     * cost는 반올림 전 원본 정밀도(quantity × avgPrice 원본값)로 계산 후 스케일을 적용하고,
     * avgPrice는 응답에 나가기 직전 별도로 반올림된다 — 그래서 quantity × 응답의 avgPrice로
     * 재계산하면 반올림 오차만큼 어긋날 수 있다(docs/ROADMAP.md 「GET /v1/portfolio 응답 예시」
     * 절 「주의」에 명문화된 의도된 동작, code-reviewer 2차 검증에서 발견). 60000.75 × 10 =
     * 600007.5 → HALF_UP scale 0 → 600008(cost)이지만, avgPrice 자체는 60000.75 → 60001로
     * 반올림돼 응답에 나간다(10 × 60001 = 600010 ≠ 600008).
     */
    @Test
    void portfolioItemCostUsesRawPrecisionWhileAvgPriceIsRoundedSeparately() {
        createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000.75"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> item = itemsOf(bodyOf(result)).get(0);
        assertThat(item.get("avgPrice")).isEqualTo("60001");
        assertThat(item.get("cost")).isEqualTo("600008");
    }

    @Test
    void portfolioItemAndTopLevelValuationFieldsArePresentButNull() {
        createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);

        assertThat(body.containsKey("totalEvaluationKrw")).isTrue();
        assertThat(body.get("totalEvaluationKrw")).isNull();
        assertThat(body.containsKey("totalUnrealizedPnl")).isTrue();
        assertThat(body.get("totalUnrealizedPnl")).isNull();

        Map<String, Object> item = itemsOf(body).get(0);
        assertThat(item.containsKey("evaluationKrw")).isTrue();
        assertThat(item.get("evaluationKrw")).isNull();
        assertThat(item.containsKey("unrealizedPnl")).isTrue();
        assertThat(item.get("unrealizedPnl")).isNull();
        assertThat(item.containsKey("weight")).isTrue();
        assertThat(item.get("weight")).isNull();
    }

    /** items는 GET /v1/assets와 동일하게 id DESC(최신 등록순)로 응답한다(code-reviewer 2차 검증 지적). */
    @Test
    void portfolioReturnsItemsInMostRecentlyRegisteredFirstOrder() {
        createAsset(tokenA, stockRequest("A001", "종목1", "1", "1000"));
        createAsset(tokenA, stockRequest("A002", "종목2", "1", "1000"));
        createAsset(tokenA, stockRequest("A003", "종목3", "1", "1000"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenA);

        List<Map<String, Object>> items = itemsOf(bodyOf(result));
        assertThat(items).extracting(item -> item.get("ticker"))
                .containsExactly("A003", "A002", "A001");
    }

    @Test
    void portfolioOnlyReturnsRequestingUsersAssets() {
        createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000"));

        MvcTestResult result = authorizedGet("/v1/portfolio", tokenB);

        assertThat(result).hasStatusOk();
        assertThat(itemsOf(bodyOf(result))).isEmpty();
    }

    @Test
    void portfolioWithoutTokenReturnsUnauthorized() {
        assertThat(mvc.get().uri("/v1/portfolio").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("UNAUTHORIZED");
    }

    private MvcTestResult signup(String email, String password) {
        return mvc.post().uri("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))
                .exchange();
    }

    private MvcTestResult createAsset(String token, String body) {
        return mvc.post().uri("/v1/assets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult authorizedGet(String uri, String token) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private String accessTokenOf(MvcTestResult result) {
        return (String) bodyOf(result).get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> totalCostByCurrencyOf(Map<String, Object> body) {
        return (Map<String, String>) body.get("totalCostByCurrency");
    }

    private Map<String, Object> itemByTicker(List<Map<String, Object>> items, String ticker) {
        return items.stream().filter(item -> ticker.equals(item.get("ticker"))).findFirst()
                .orElseThrow(() -> new IllegalStateException("ticker " + ticker + " not found in items"));
    }

    private Map<String, Object> itemByTickerAndCost(List<Map<String, Object>> items, String ticker, String cost) {
        return items.stream()
                .filter(item -> ticker.equals(item.get("ticker")) && cost.equals(item.get("cost")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ticker " + ticker + " with cost " + cost + " not found in items"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(MvcTestResult result) {
        try {
            return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stockRequest(String ticker, String name, String quantity, String avgPrice) {
        return stockRequestWithCurrency(ticker, name, quantity, avgPrice, "KRW");
    }

    private static String stockRequestWithCurrency(String ticker, String name, String quantity, String avgPrice,
            String currency) {
        return """
                {"ticker":"%s","name":"%s","assetType":"STOCK","currency":"%s","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, currency, quantity, avgPrice);
    }

    private static String coinRequest(String ticker, String name, String quantity, String avgPrice) {
        return """
                {"ticker":"%s","name":"%s","assetType":"COIN","currency":"KRW","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, quantity, avgPrice);
    }

    private static String cashRequest(String ticker, String name, String quantity, String avgPrice) {
        return """
                {"ticker":"%s","name":"%s","assetType":"CASH","currency":"KRW","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, quantity, avgPrice);
    }
}
