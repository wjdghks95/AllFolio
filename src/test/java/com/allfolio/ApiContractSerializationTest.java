package com.allfolio;

import com.allfolio.domain.AssetType;
import com.allfolio.web.dto.AssetResponse;
import com.allfolio.web.dto.PortfolioItemResponse;
import com.allfolio.web.dto.SimulateAvgPriceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 006 — 컨트롤러가 아직 없는 시점(Task 012~015 이전)에 DTO 직렬화 결과
 * JSON 자체를 고정한다. @JsonTest는 실제 ObjectMapper(Jackson 자동설정 포함)만 띄우는 슬라이스
 * 테스트라 Testcontainers 없이 수초 내 끝난다. 문서(산문)만으로 계약을 두면 나중에 어기는 걸
 * 못 막지만, 테스트로 두면 어긋나는 순간 빌드가 깨진다.
 */
@JsonTest
class ApiContractSerializationTest {

    @Autowired
    private JacksonTester<AssetResponse> assetResponseJson;

    @Autowired
    private JacksonTester<PortfolioItemResponse> portfolioItemResponseJson;

    @Autowired
    private JacksonTester<SimulateAvgPriceResponse> simulateAvgPriceResponseJson;

    /** assetType이 "STOCK" 문자열로 직렬화되는가 (Task 005 리뷰 후속 조치). */
    @Test
    void assetType은_대문자_문자열로_직렬화된다() throws Exception {
        AssetResponse response = new AssetResponse(
                UUID.randomUUID(), "005930", "삼성전자", AssetType.STOCK, "KRW",
                "10", "60000", 0, Instant.parse("2026-08-05T10:00:00Z"));

        assertThat(assetResponseJson.write(response))
                .extractingJsonPathStringValue("$.assetType")
                .isEqualTo("STOCK");
    }

    /** quantity/avgPrice가 JSON 문자열이어야 한다 — 숫자로 나가면 부동소수 손실 위험(프로젝트 규칙). */
    @Test
    void 금액과_수량은_숫자가_아닌_문자열로_직렬화된다() throws Exception {
        AssetResponse response = new AssetResponse(
                UUID.randomUUID(), "005930", "삼성전자", AssetType.STOCK, "KRW",
                "10", "60000", 0, Instant.parse("2026-08-05T10:00:00Z"));

        var json = assetResponseJson.write(response);
        assertThat(json).extractingJsonPathStringValue("$.quantity").isEqualTo("10");
        assertThat(json).extractingJsonPathStringValue("$.avgPrice").isEqualTo("60000");
        // 문자열이 아니라 숫자로 나갔다면 JSON 원문에 따옴표 없는 60000이 등장한다.
        assertThat(json.getJson()).doesNotContain(":60000,").doesNotContain(":60000}");
    }

    /** Instant는 숫자 타임스탬프가 아닌 ISO-8601 문자열로 나가야 프론트 파싱이 깨지지 않는다. */
    @Test
    void updatedAt은_ISO8601_문자열로_직렬화된다() throws Exception {
        AssetResponse response = new AssetResponse(
                UUID.randomUUID(), "005930", "삼성전자", AssetType.STOCK, "KRW",
                "10", "60000", 0, Instant.parse("2026-08-05T10:00:00Z"));

        assertThat(assetResponseJson.write(response))
                .extractingJsonPathStringValue("$.updatedAt")
                .isEqualTo("2026-08-05T10:00:00Z");
    }

    /**
     * evaluationKrw/unrealizedPnl/weight가 null이어도 키 자체는 남아 있어야 한다 — 키가 사라지면
     * 프론트 타입(`weight: string | null`)이 거짓말이 된다. hasJsonPathValue는 키 부재와 null 값을
     * 구분하지 못하므로 extractingJsonPathValue(...).isNull()로 확인한다.
     */
    @Test
    void 미구현_평가지표_필드는_null_값으로_키가_유지된다() throws Exception {
        PortfolioItemResponse response = new PortfolioItemResponse(
                UUID.randomUUID(), "005930", "삼성전자", AssetType.STOCK, "KRW",
                "10", "60000", "600000", null, null, null);

        var json = portfolioItemResponseJson.write(response);
        assertThat(json).hasJsonPath("$.evaluationKrw");
        assertThat(json).hasJsonPath("$.unrealizedPnl");
        assertThat(json).hasJsonPath("$.weight");
        assertThat(json).extractingJsonPathValue("$.evaluationKrw").isNull();
        assertThat(json).extractingJsonPathValue("$.unrealizedPnl").isNull();
        assertThat(json).extractingJsonPathValue("$.weight").isNull();
    }

    /** ROADMAP 골든 케이스: 60,000원×10주 + 55,000원×5주 → 58,333원 (HALF_UP, scale 0). */
    @Test
    void 시뮬레이터_골든_케이스가_58333으로_직렬화된다() throws Exception {
        BigDecimal currentAvg = new BigDecimal("60000");
        BigDecimal totalCost = new BigDecimal("60000").multiply(new BigDecimal("10"))
                .add(new BigDecimal("55000").multiply(new BigDecimal("5")));
        BigDecimal totalQuantity = new BigDecimal("15");
        BigDecimal expectedAvg = totalCost.divide(totalQuantity, 0, RoundingMode.HALF_UP);

        SimulateAvgPriceResponse response = SimulateAvgPriceResponse.of(currentAvg, expectedAvg);

        assertThat(expectedAvg).isEqualByComparingTo("58333");
        assertThat(simulateAvgPriceResponseJson.write(response))
                .extractingJsonPathStringValue("$.expectedAvgPrice")
                .isEqualTo("58333");
    }
}
