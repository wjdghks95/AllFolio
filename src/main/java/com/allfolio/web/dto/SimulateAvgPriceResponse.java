package com.allfolio.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 물타기 시뮬레이터 응답 (Task 015, docs/ROADMAP.md).
 * currentWeight/expectedWeight는 외부 시세 연동(Task 023) 전까지 null.
 */
public record SimulateAvgPriceResponse(
        String currentAvgPrice,
        String expectedAvgPrice,
        BigDecimal currentWeight,
        BigDecimal expectedWeight,
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
