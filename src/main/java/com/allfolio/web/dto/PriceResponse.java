package com.allfolio.web.dto;

import java.time.Instant;

public record PriceResponse(String amount, String currency, Instant asOf) {
}
