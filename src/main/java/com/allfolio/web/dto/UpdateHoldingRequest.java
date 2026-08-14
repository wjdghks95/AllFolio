package com.allfolio.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateHoldingRequest(

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
