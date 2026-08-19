package com.allfolio.web.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 물타기 시뮬레이터 응답 (Task 015, docs/ROADMAP.md).
 * currentWeight/expectedWeight는 외부 시세 연동(Task 023) 전까지 null.
 * BigDecimal이 아닌 String인 이유: 이 프로젝트는 금액·수량·비중 등 모든 금융성 수치를
 * JSON에서 문자열로 직렬화하는 계약을 쓴다(docs/ROADMAP.md Task 006) — BigDecimal 필드를
 * 그대로 두면 Jackson이 JSON 숫자로 내보내 계약이 깨진다.
 */
public record SimulateAvgPriceResponse(
        String currentAvgPrice,
        String expectedAvgPrice,
        String expectedQuantity,
        String currentWeight,
        String expectedWeight,
        Instant calculatedAt
) {

    /**
     * expectedQuantity는 통화/자산 종류와 무관하게 항상 scale 8로 직렬화한다
     * (docs/ROADMAP.md 「시뮬레이터 응답 예시」 골든 케이스 주석 참조).
     */
    public static SimulateAvgPriceResponse of(BigDecimal currentAvg, BigDecimal expectedAvg, BigDecimal expectedQuantity) {
        return new SimulateAvgPriceResponse(
                currentAvg.toPlainString(),
                expectedAvg.toPlainString(),
                expectedQuantity.setScale(8, RoundingMode.HALF_UP).toPlainString(),
                null,
                null,
                Instant.now()
        );
    }
}
