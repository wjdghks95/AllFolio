package com.allfolio.web.dto;

import com.allfolio.domain.AssetType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateAssetRequest(

        @NotBlank
        @Size(max = 20)
        String ticker,

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        AssetType assetType,

        @NotBlank
        @Size(max = 3)
        String currency,

        @NotNull
        @DecimalMin("0")
        @Digits(integer = 20, fraction = 8)
        BigDecimal quantity,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 20, fraction = 8)
        BigDecimal avgPrice
) {
}
