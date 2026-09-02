package com.allfolio.infra.price;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "allfolio.upbit")
public record UpbitProperties(
        @NotBlank
        String baseUrl
) {
}
