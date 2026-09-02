package com.allfolio.infra.price;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 공공데이터포털 "금융위원회_주식시세정보" API 설정. serviceKey는 환경변수
 * ALLFOLIO_STOCK_SERVICE_KEY로 주입한다 (JwtProperties.secret과 동일한 원칙 —
 * 하드코딩 방지, 미설정 시 부팅 실패).
 */
@Validated
@ConfigurationProperties(prefix = "allfolio.stock")
public record StockProperties(
        @NotBlank
        String baseUrl,

        @NotBlank
        String serviceKey
) {
}
