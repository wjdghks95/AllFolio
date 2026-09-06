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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 024 — 거래 이력 조회·입력(GET/POST /v1/assets/{id}/transactions) 통합 검증.
 * AssetIntegrationTest의 MockMvcTester 컨벤션(post/authorizedXxx 헬퍼, bodyOf)을 그대로 따르되,
 * 저장소 관례(각 통합 테스트가 헬퍼를 독립 보유)에 맞춰 복붙한다.
 */
@AutoConfigureMockMvc
class TransactionIntegrationTest extends AbstractIntegrationTest {

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

    /**
     * 골든 케이스(docs/ROADMAP.md): 60,000원×10주 보유 중 55,000원×5주를 추가 매수하면
     * 평단가는 58,333원. avgPrice는 scale까지 정확한 문자열로 단언한다(.claude/rules/testing.md).
     */
    @Test
    void buyRecalculatesWeightedAveragePriceExactly() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("BUY", "55000", "5", "2024-01-01T00:00:00Z"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        Map<String, Object> holding = holdingOf(result);
        assertThat(holding.get("avgPrice")).isEqualTo("58333");
        assertThat(new BigDecimal((String) holding.get("quantity"))).isEqualByComparingTo(new BigDecimal("15"));
    }

    @Test
    void sellDeductsQuantityAndKeepsAvgPriceUnchanged() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("SELL", "65000", "3", "2024-01-01T00:00:00Z"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        Map<String, Object> holding = holdingOf(result);
        assertThat(new BigDecimal((String) holding.get("quantity"))).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(new BigDecimal((String) holding.get("avgPrice"))).isEqualByComparingTo(new BigDecimal("60000"));
    }

    @Test
    void sellExceedingHeldQuantityReturnsInsufficientQuantity() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("SELL", "65000", "15", "2024-01-01T00:00:00Z"));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("INSUFFICIENT_QUANTITY");
    }

    @Test
    void dividendLeavesQuantityAndAvgPriceCompletelyUnchanged() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("DIVIDEND", "1000", "1", "2024-01-01T00:00:00Z"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        Map<String, Object> holding = holdingOf(result);
        assertThat(new BigDecimal((String) holding.get("quantity"))).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(new BigDecimal((String) holding.get("avgPrice"))).isEqualByComparingTo(new BigDecimal("60000"));
    }

    /**
     * code-reviewer 실측 회귀 테스트(버그 3): DIVIDEND는 quantity/avgPrice뿐 아니라 holding.version도
     * 완전 불변이어야 한다. holding.update()를 호출하면 값이 같아도 dirty checking으로 version이
     * 증가한다(수정 전 버그) — 자산 생성 시 자동 BUY 1건으로 이미 version=0인 holding에서
     * DIVIDEND 이후에도 version=0을 유지하는지로 검증한다.
     */
    @Test
    void dividendDoesNotIncrementHoldingVersion() {
        MvcTestResult created = createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000"));
        String assetId = idOf(created);
        // POST /v1/assets 응답은 AssetResponse 그대로라 holding이 별도로 중첩돼 있지 않다
        // (TransactionResponse와 달리) — version 필드가 최상위에 바로 있다.
        Number versionBefore = (Number) bodyOf(created).get("version");

        MvcTestResult result = authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("DIVIDEND", "1000", "1", "2024-01-01T00:00:00Z"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        Number versionAfter = (Number) holdingOf(result).get("version");
        assertThat(versionAfter.longValue()).isEqualTo(versionBefore.longValue());
    }

    /**
     * code-reviewer 실측 회귀 테스트(버그 2): 파싱 불가능한 cursor 값은 500이 아닌 400
     * VALIDATION_ERROR로 응답해야 한다(저장소 컨벤션 — GET /v1/assets의 UUID 커서와 동일).
     */
    @Test
    void invalidCursorReturnsValidationErrorInsteadOf500() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        assertThat(authorizedGet(tokenA, "/v1/assets/" + assetId + "/transactions?cursor=garbage"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    /**
     * code-reviewer 실측 회귀 테스트(버그 1): 같은 밀리초 내 서로 다른 나노초를 가진 거래를
     * 커서 페이지네이션으로 순회해도 누락 없이 전부 조회돼야 한다. 수정 전에는 커서가
     * epochMillis로 절삭돼 같은 밀리초 구간의 행이 타이브레이크(tradedAt 정확 일치)에서
     * 빠졌다(실측: 4건 중 2건 소실).
     */
    @Test
    void listTransactionsDoesNotDropRowsWithinSameMillisecond() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));
        // 자동 생성된 BUY(quantity=10)가 이미 1건 있다. 동일 밀리초, 서로 다른 나노초의 거래 4건을 추가한다.
        authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("BUY", "1", "1", "2024-06-01T00:00:00.100000001Z"));
        authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("BUY", "1", "2", "2024-06-01T00:00:00.100000002Z"));
        authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("BUY", "1", "3", "2024-06-01T00:00:00.100000003Z"));
        authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("BUY", "1", "4", "2024-06-01T00:00:00.100000004Z"));

        List<Map<String, Object>> allItems = new ArrayList<>();
        String cursor = null;
        do {
            String uri = "/v1/assets/" + assetId + "/transactions?limit=2"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            MvcTestResult page = authorizedGet(tokenA, uri);
            assertThat(page).hasStatusOk();
            Map<String, Object> body = bodyOf(page);
            allItems.addAll(itemsOf(body));
            cursor = (String) body.get("nextCursor");
        } while (cursor != null);

        // 자동 생성 BUY 1건 + 나노초만 다른 4건 = 총 5건이 전부 조회돼야 한다.
        assertThat(allItems).hasSize(5);
    }

    @Test
    void accessingOtherUsersAssetViaTransactionEndpointsReturnsAssetNotFound() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        assertThat(authorizedPost(tokenB, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("BUY", "60000", "1", "2024-01-01T00:00:00Z")))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("ASSET_NOT_FOUND");

        assertThat(authorizedGet(tokenB, "/v1/assets/" + assetId + "/transactions"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("ASSET_NOT_FOUND");
    }

    /**
     * tradedAt DESC 정렬 + 커서 페이지네이션(limit=2)으로 5건(자동 생성 BUY 1건 포함)을 중복/누락 없이
     * 순서대로 순회할 수 있는지 검증한다. 거래별 quantity 값을 식별자로 사용해 순서를 확인한다.
     */
    @Test
    void listTransactionsPaginatesByCursorInDescendingTradedAtOrder() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));
        // 자동 생성된 BUY(quantity=10, tradedAt=Instant.now())가 이미 1건 있다.
        authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("BUY", "1", "1", "2020-01-01T00:00:00Z"));
        authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("BUY", "1", "2", "2021-01-01T00:00:00Z"));
        authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("BUY", "1", "3", "2022-01-01T00:00:00Z"));
        authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("BUY", "1", "4", "2099-01-01T00:00:00Z"));

        MvcTestResult page1 = authorizedGet(tokenA, "/v1/assets/" + assetId + "/transactions?limit=2");
        assertThat(page1).hasStatusOk();
        Map<String, Object> page1Body = bodyOf(page1);
        List<Map<String, Object>> page1Items = itemsOf(page1Body);
        assertThat(page1Items).hasSize(2);
        String cursor1 = (String) page1Body.get("nextCursor");
        assertThat(cursor1).isNotNull();

        MvcTestResult page2 = authorizedGet(tokenA,
                "/v1/assets/" + assetId + "/transactions?limit=2&cursor=" + cursor1);
        assertThat(page2).hasStatusOk();
        Map<String, Object> page2Body = bodyOf(page2);
        List<Map<String, Object>> page2Items = itemsOf(page2Body);
        assertThat(page2Items).hasSize(2);
        String cursor2 = (String) page2Body.get("nextCursor");
        assertThat(cursor2).isNotNull();

        MvcTestResult page3 = authorizedGet(tokenA,
                "/v1/assets/" + assetId + "/transactions?limit=2&cursor=" + cursor2);
        assertThat(page3).hasStatusOk();
        Map<String, Object> page3Body = bodyOf(page3);
        List<Map<String, Object>> page3Items = itemsOf(page3Body);
        assertThat(page3Items).hasSize(1);
        assertThat(page3Body.get("nextCursor")).isNull();

        List<Map<String, Object>> allItems = new ArrayList<>();
        allItems.addAll(page1Items);
        allItems.addAll(page2Items);
        allItems.addAll(page3Items);

        // tradedAt DESC 예상 순서: 2099(qty=4), 자동생성(qty=10, 대략 now), 2022(qty=3), 2021(qty=2), 2020(qty=1)
        List<BigDecimal> expectedOrder = List.of(
                new BigDecimal("4"), new BigDecimal("10"), new BigDecimal("3"),
                new BigDecimal("2"), new BigDecimal("1"));
        List<BigDecimal> actualOrder = allItems.stream()
                .map(item -> new BigDecimal((String) item.get("quantity")))
                .toList();

        assertThat(actualOrder).hasSize(5);
        for (int i = 0; i < expectedOrder.size(); i++) {
            assertThat(actualOrder.get(i)).isEqualByComparingTo(expectedOrder.get(i));
        }
    }

    @Test
    void creatingAssetAutomaticallyRecordsInitialBuyTransaction() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = authorizedGet(tokenA, "/v1/assets/" + assetId + "/transactions");

        assertThat(result).hasStatusOk();
        List<Map<String, Object>> items = itemsOf(bodyOf(result));
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().get("txType")).isEqualTo("BUY");
    }

    @Test
    void cashAssetTransactionAlwaysForcesAvgPriceToOneRegardlessOfRequestedPrice() {
        String assetId = idOf(createAsset(tokenA, cashRequest("KRW-CASH", "현금", "100000", "1")));

        MvcTestResult result = authorizedPost(tokenA, "/v1/assets/" + assetId + "/transactions",
                transactionRequest("BUY", "12345", "5000", "2024-01-01T00:00:00Z"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(holdingOf(result).get("avgPrice")).isEqualTo("1");
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

    private MvcTestResult authorizedGet(String token, String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult authorizedPost(String token, String uri, String body) {
        return mvc.post().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private String accessTokenOf(MvcTestResult result) {
        return (String) bodyOf(result).get("accessToken");
    }

    private String idOf(MvcTestResult result) {
        return (String) bodyOf(result).get("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> holdingOf(MvcTestResult result) {
        return (Map<String, Object>) bodyOf(result).get("holding");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
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
        return """
                {"ticker":"%s","name":"%s","assetType":"STOCK","currency":"KRW","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, quantity, avgPrice);
    }

    private static String cashRequest(String ticker, String name, String quantity, String avgPrice) {
        return """
                {"ticker":"%s","name":"%s","assetType":"CASH","currency":"KRW","quantity":"%s","avgPrice":"%s"}
                """.formatted(ticker, name, quantity, avgPrice);
    }

    private static String transactionRequest(String txType, String price, String quantity, String tradedAt) {
        return """
                {"txType":"%s","price":"%s","quantity":"%s","tradedAt":"%s"}
                """.formatted(txType, price, quantity, tradedAt);
    }
}
