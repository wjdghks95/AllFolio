package com.allfolio.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * amount는 외부 시세 소스(업비트/KIS/환율)의 원본 정밀도를 그대로 보존한다 — scale 정규화는
 * 호출자(PriceService)가 PrecisionScale로 책임진다.
 */
public record Price(BigDecimal amount, String currency, Instant asOf) {
}
