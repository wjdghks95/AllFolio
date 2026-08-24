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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 012 — 자산 CRUD 5개 엔드포인트의 통합 검증. AuthIntegrationTest의
 * MockMvcTester 컨벤션(post/authorizedXxx 헬퍼, bodyOf)을 그대로 따른다.
 */
@AutoConfigureMockMvc
class AssetIntegrationTest extends AbstractIntegrationTest {

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
    void createStockAssetReturnsCreatedWithFields() {
        MvcTestResult result = createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("ticker")).isEqualTo("005930");
        assertThat(body.get("name")).isEqualTo("삼성전자");
        assertThat(body.get("assetType")).isEqualTo("STOCK");
        assertThat(body.get("currency")).isEqualTo("KRW");
        assertThat(body.get("quantity")).isEqualTo("10");
        assertThat(body.get("avgPrice")).isEqualTo("60000");
        assertThat(body.get("version")).isEqualTo(0);
        assertThat(body.get("id")).isNotNull();
        assertThat(body.get("updatedAt")).isNotNull();
    }

    @Test
    void createCashAssetIgnoresRequestedAvgPriceAndForcesOne() {
        MvcTestResult result = createAsset(tokenA, cashRequest("KRW-CASH", "현금", "100000", "99999"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(bodyOf(result).get("avgPrice")).isEqualTo("1");
    }

    @Test
    void listAssetsPaginatesByCursorWithoutDuplicateOrMissingItems() {
        createAsset(tokenA, stockRequest("A001", "종목1", "1", "1000"));
        createAsset(tokenA, stockRequest("A002", "종목2", "1", "1000"));
        createAsset(tokenA, stockRequest("A003", "종목3", "1", "1000"));

        MvcTestResult page1 = authorizedGet("/v1/assets?limit=2", tokenA);
        assertThat(page1).hasStatusOk();
        Map<String, Object> page1Body = bodyOf(page1);
        List<Map<String, Object>> page1Items = itemsOf(page1Body);
        assertThat(page1Items).hasSize(2);
        String nextCursor = (String) page1Body.get("nextCursor");
        assertThat(nextCursor).isNotNull();

        MvcTestResult page2 = authorizedGet("/v1/assets?limit=2&cursor=" + nextCursor, tokenA);
        assertThat(page2).hasStatusOk();
        Map<String, Object> page2Body = bodyOf(page2);
        List<Map<String, Object>> page2Items = itemsOf(page2Body);
        assertThat(page2Items).hasSize(1);
        assertThat(page2Body.get("nextCursor")).isNull();

        List<Object> allTickers = new ArrayList<>();
        page1Items.forEach(item -> allTickers.add(item.get("ticker")));
        page2Items.forEach(item -> allTickers.add(item.get("ticker")));
        assertThat(allTickers).containsExactlyInAnyOrder("A001", "A002", "A003");
    }

    @Test
    void getAssetReturnsCreatedAsset() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = authorizedGet("/v1/assets/" + assetId, tokenA);

        assertThat(result).hasStatusOk();
        assertThat(bodyOf(result).get("ticker")).isEqualTo("005930");
    }

    @Test
    void accessingOtherUsersAssetViaGetReturnsAssetNotFound() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        assertThat(authorizedGet("/v1/assets/" + assetId, tokenB))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    void accessingOtherUsersAssetViaPutReturnsAssetNotFound() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        assertThat(authorizedPut("/v1/assets/" + assetId + "/holdings", tokenB,
                updateHoldingRequest("20", "70000", 0)))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    void accessingOtherUsersAssetViaDeleteReturnsAssetNotFound() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        assertThat(authorizedDelete("/v1/assets/" + assetId, tokenB))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    void updateHoldingIncrementsVersionByOne() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = authorizedPut("/v1/assets/" + assetId + "/holdings", tokenA,
                updateHoldingRequest("20", "70000", 0));

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("quantity")).isEqualTo("20");
        assertThat(body.get("avgPrice")).isEqualTo("70000");
        assertThat(body.get("version")).isEqualTo(1);
    }

    @Test
    void updateHoldingWithStaleVersionReturnsHoldingConflict() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));
        authorizedPut("/v1/assets/" + assetId + "/holdings", tokenA, updateHoldingRequest("20", "70000", 0));

        MvcTestResult result = authorizedPut("/v1/assets/" + assetId + "/holdings", tokenA,
                updateHoldingRequest("30", "80000", 0));

        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("HOLDING_CONFLICT");
    }

    @Test
    void updateCashHoldingIgnoresRequestedAvgPriceAndKeepsOne() {
        String assetId = idOf(createAsset(tokenA, cashRequest("KRW-CASH", "현금", "100000", "1")));

        MvcTestResult result = authorizedPut("/v1/assets/" + assetId + "/holdings", tokenA,
                updateHoldingRequest("200000", "12345", 0));

        assertThat(result).hasStatusOk();
        assertThat(bodyOf(result).get("avgPrice")).isEqualTo("1");
    }

    @Test
    void updateNonCashHoldingWithNullAvgPriceReturnsValidationError() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = mvc.put().uri("/v1/assets/" + assetId + "/holdings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":\"20\",\"avgPrice\":null,\"version\":0}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void deleteAssetReturnsNoContentThenSubsequentGetReturnsNotFound() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        assertThat(authorizedDelete("/v1/assets/" + assetId, tokenA)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(authorizedGet("/v1/assets/" + assetId, tokenA))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    void registeringSecondAssetWithSameTickerSucceeds() {
        assertThat(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")))
                .hasStatus(HttpStatus.CREATED);
        assertThat(createAsset(tokenA, stockRequest("005930", "삼성전자", "5", "65000")))
                .hasStatus(HttpStatus.CREATED);
    }

    /**
     * limit이 @Min/@Max 범위를 벗어나면 400 VALIDATION_ERROR여야 한다. 클래스 레벨 @Validated를
     * 붙였을 때 이 경로가 500 INTERNAL_ERROR로 새는 회귀가 있었다(code-reviewer 지적,
     * docs/ROADMAP.md Task 012).
     */
    @Test
    void listAssetsWithLimitBelowMinimumReturnsValidationError() {
        assertThat(authorizedGet("/v1/assets?limit=0", tokenA))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void listAssetsWithLimitAboveMaximumReturnsValidationError() {
        assertThat(authorizedGet("/v1/assets?limit=101", tokenA))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
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

    private MvcTestResult authorizedPut(String uri, String token, String body) {
        return mvc.put().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult authorizedDelete(String uri, String token) {
        return mvc.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private String accessTokenOf(MvcTestResult result) {
        return (String) bodyOf(result).get("accessToken");
    }

    private String idOf(MvcTestResult result) {
        return (String) bodyOf(result).get("id");
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

    private static String updateHoldingRequest(String quantity, String avgPrice, int version) {
        return """
                {"quantity":"%s","avgPrice":"%s","version":%d}
                """.formatted(quantity, avgPrice, version);
    }
}
