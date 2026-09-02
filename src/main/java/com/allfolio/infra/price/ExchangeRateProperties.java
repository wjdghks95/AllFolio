package com.allfolio.infra.price;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "allfolio.exchange-rate")
public record ExchangeRateProperties(
        @NotBlank
        String baseUrl
) {
}
