package com.allfolio.web.dto;

import java.math.BigDecimal;
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
        String currentWeight,
        String expectedWeight,
        Instant calculatedAt
) {

    public static SimulateAvgPriceResponse of(BigDecimal currentAvg, BigDecimal expectedAvg) {
        return new SimulateAvgPriceResponse(
                currentAvg.toPlainString(),
                expectedAvg.toPlainString(),
                null,
                null,
                Instant.now()
        );
    }
}
