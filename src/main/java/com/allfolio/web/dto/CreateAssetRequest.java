package com.allfolio.web.dto;

import com.allfolio.domain.AssetType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * CASH 자산은 평단가 개념이 없어 avgPrice를 null로 보낼 수 있다 — 서버가 1을 강제 삽입한다
 * (docs/ROADMAP.md Task 006 확정 결정 #1). {@link AvgPriceRequiredUnlessCash}가 이 레코드 전체를
 * 대상으로 assetType에 따른 필수 여부를 검증한다.
 */
@AvgPriceRequiredUnlessCash
public record CreateAssetRequest(

        @NotBlank
        @Size(max = 20)
        String ticker,

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        AssetType assetType,

        // ISO 4217 형식(영문 대문자 3자리)만 통과시킨다. 통화 목록을 KRW|USD로 하드코딩하지 않는 이유는
        // Phase 3 환율 연동(Task 023)에서 통화가 늘 때마다 서버 코드를 고치지 않기 위함이다.
        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$")
        String currency,

        @NotNull
        @DecimalMin("0")
        @Digits(integer = 20, fraction = 8)
        BigDecimal quantity,

        // CASH일 때는 null 허용 — AvgPriceRequiredUnlessCash가 그 외 타입에서 non-null을 강제한다.
        @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 20, fraction = 8)
        BigDecimal avgPrice
) {
}
