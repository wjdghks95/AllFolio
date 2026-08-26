package com.allfolio;

import com.allfolio.domain.Holding;
import com.allfolio.domain.repository.HoldingRepository;
import com.allfolio.domain.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 015 — POST /v1/simulate/avg-price(물타기 시뮬레이터, F006) 통합 검증.
 * AssetIntegrationTest/PortfolioIntegrationTest의 MockMvcTester 컨벤션(post/authorizedXxx 헬퍼,
 * bodyOf, 각 통합 테스트가 헬퍼를 독립 보유)을 그대로 따른다.
 */
@AutoConfigureMockMvc
class SimulateIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        tokenA = accessTokenOf(signup("trader-a@example.com", "correct-horse-battery"));
        tokenB = accessTokenOf(signup("trader-b@example.com", "correct-horse-battery"));
    }

    /**
     * 골든 케이스(docs/ROADMAP.md 「API 규격」): 60,000원×10주 보유 중 55,000원×5주 추가 매수 →
     * 신규 평단가 58,333원. scale까지 정확히 맞아야 하므로 compareTo가 아닌 문자열 그대로 비교한다.
     */
    @Test
    void simulateGoldenCaseReturnsExactAvgPriceAndQuantityStrings() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = authorizedPost("/v1/simulate/avg-price", tokenA,
                simulateRequest(assetId, "55000", "5"));

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("currentAvgPrice")).isEqualTo("60000");
        assertThat(body.get("expectedAvgPrice")).isEqualTo("58333");
        assertThat(body.get("expectedQuantity")).isEqualTo("15.00000000");
    }

    @Test
    void simulatingOtherUsersAssetReturnsAssetNotFound() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = authorizedPost("/v1/simulate/avg-price", tokenB,
                simulateRequest(assetId, "55000", "5"));

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    void simulatingNonExistentAssetReturnsAssetNotFound() {
        MvcTestResult result = authorizedPost("/v1/simulate/avg-price", tokenA,
                simulateRequest(UUID.randomUUID().toString(), "55000", "5"));

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    void simulatingWithoutTokenReturnsUnauthorized() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = mvc.post().uri("/v1/simulate/avg-price")
                .contentType(MediaType.APPLICATION_JSON)
                .content(simulateRequest(assetId, "55000", "5"))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void simulatingWithNonPositiveAdditionalQuantityReturnsValidationError() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = authorizedPost("/v1/simulate/avg-price", tokenA,
                simulateRequest(assetId, "55000", "0"));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void simulatingWithNonPositiveAdditionalPriceReturnsValidationError() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));

        MvcTestResult result = authorizedPost("/v1/simulate/avg-price", tokenA,
                simulateRequest(assetId, "0", "5"));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void simulatingWithNullAssetIdReturnsValidationError() {
        MvcTestResult result = authorizedPost("/v1/simulate/avg-price", tokenA,
                """
                {"assetId":null,"additionalPrice":"55000","additionalQuantity":"5"}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").asString().isEqualTo("VALIDATION_ERROR");
    }

    /** USD는 「금융 정밀도 규칙」상 scale 4 — KRW(0)/COIN(8)과 다른 분기가 실제로 타는지 확인한다. */
    @Test
    void simulatingUsdAssetUsesFourDecimalScale() {
        String assetId = idOf(createAsset(tokenA, stockRequest("AAPL", "애플", "10", "150.1234", "USD")));

        MvcTestResult result = authorizedPost("/v1/simulate/avg-price", tokenA,
                simulateRequest(assetId, "160.98765", "5"));

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("currentAvgPrice")).isEqualTo("150.1234");
        assertThat(body.get("expectedAvgPrice")).isEqualTo("153.7448");
        assertThat(body.get("expectedQuantity")).isEqualTo("15.00000000");
    }

    /**
     * allfolio.simulation.duration 메트릭이 실제로 기록되는지 자동 검증한다(Task 014가 준비한
     * 히스토그램 설정을 Task 015가 실제로 채우는지 — 지금까지는 bootRun 수동 확인뿐이었다).
     */
    @Test
    void successfulSimulationRecordsMetric() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));
        long before = meterRegistry.find("allfolio.simulation.duration").timer() == null
                ? 0
                : meterRegistry.find("allfolio.simulation.duration").timer().count();

        MvcTestResult result = authorizedPost("/v1/simulate/avg-price", tokenA,
                simulateRequest(assetId, "55000", "5"));

        assertThat(result).hasStatusOk();
        long after = meterRegistry.find("allfolio.simulation.duration").timer().count();
        assertThat(after).isEqualTo(before + 1);
    }

    /**
     * 시뮬레이터는 DB에 쓰지 않는다(ROADMAP Task 015 불변식) — HoldingRepository로 직접 재조회해
     * quantity/avgPrice/version/updatedAt이 호출 전후로 완전히 동일한지 증명한다.
     */
    @Test
    void simulateDoesNotMutateHoldingInDatabase() {
        String assetId = idOf(createAsset(tokenA, stockRequest("005930", "삼성전자", "10", "60000")));
        Holding before = holdingRepository.findByAsset_Id(UUID.fromString(assetId)).orElseThrow();

        MvcTestResult result = authorizedPost("/v1/simulate/avg-price", tokenA,
                simulateRequest(assetId, "55000", "5"));

        assertThat(result).hasStatusOk();
        Holding after = holdingRepository.findByAsset_Id(UUID.fromString(assetId)).orElseThrow();
        assertThat(after.getQuantity()).isEqualByComparingTo(before.getQuantity());
        assertThat(after.getAvgPrice()).isEqualByComparingTo(before.getAvgPrice());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    /**
     * CASH 자산은 avgPrice가 항상 1로 강제될 뿐, 시뮬레이터가 별도로 거부하지 않는다는 게 확정된
     * 설계 결정(docs/ROADMAP.md Task 006, CLAUDE.md 「주요 도메인 개념」).
     */
    @Test
    void simulatingCashAssetSucceeds() {
        String assetId = idOf(createAsset(tokenA, cashRequest("KRW-CASH", "현금", "100000")));

        MvcTestResult result = authorizedPost("/v1/simulate/avg-price", tokenA,
                simulateRequest(assetId, "1", "50000"));

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("currentAvgPrice")).isEqualTo("1");
        assertThat(body.get("expectedAvgPrice")).isEqualTo("1");
        assertThat(body.get("expectedQuantity")).isEqualTo("150000.00000000");
    }

    /**
     * COIN은 통화와 무관하게 항상 scale 8(「금융 정밀도 규칙」)이다. 1주×100원 보유 중 2주×50원을
     * 추가 매수하면 (100+100)/3 = 66.666666...(무한소수) → HALF_UP scale 8로 66.66666667까지
     * 올림되는지 확인한다(9번째 자리 6 → 8번째 자리 6이 7로 반올림).
     */
    @Test
    void simulatingCoinAssetRoundsHalfUpAtEighthDecimal() {
        String assetId = idOf(createAsset(tokenA, coinRequest("BTC", "비트코인", "1", "100")));

        MvcTestResult result = authorizedPost("/v1/simulate/avg-price", tokenA,
                simulateRequest(assetId, "50", "2"));

        assertThat(result).hasStatusOk();
        Map<String, Object> body = bodyOf(result);
        assertThat(body.get("currentAvgPrice")).isEqualTo("100.00000000");
        assertThat(body.get("expectedAvgPrice")).isEqualTo("66.66666667");
        assertThat(body.get("expectedQuantity")).isEqualTo("3.00000000");
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

    private MvcTestResult authorizedPost(String uri, String token, String body) {
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
    private Map<String, Object> bodyOf(MvcTestResult result) {
        try {
            return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stockRequest(String ticker, String name, String quantity, String avgPrice) {
        return stockRequest(ticker, name, quantity, avgPrice, "KRW");
    }

    private static String stockRequest(String ticker, String name, String quantity, String avgPrice,
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

    private static String cashRequest(String ticker, String name, String quantity) {
        return """
                {"ticker":"%s","name":"%s","assetType":"CASH","currency":"KRW","quantity":"%s","avgPrice":null}
                """.formatted(ticker, name, quantity);
    }

    private static String simulateRequest(String assetId, String additionalPrice, String additionalQuantity) {
        return """
                {"assetId":"%s","additionalPrice":"%s","additionalQuantity":"%s"}
                """.formatted(assetId, additionalPrice, additionalQuantity);
    }
}
