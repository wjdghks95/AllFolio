package com.allfolio.infra.price;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Twelve Data(twelvedata.com) API 설정 — 미국 주식(STOCK+USD) 시세 조회 전용.
 * apiKey는 환경변수 ALLFOLIO_TWELVEDATA_API_KEY로 주입한다.
 *
 * <p><b>apiKey에 {@code @NotBlank}를 붙이지 않은 이유</b>: {@code @Validated}가 붙은
 * {@code @ConfigurationProperties} 레코드는 Spring Boot가 바인딩 시점에 Bean Validation을
 * 실제로 수행하며, 필드가 빈 문자열이면 {@code ConfigurationPropertiesBindException}으로
 * 컨텍스트 로딩 자체가 실패한다({@code StockProperties.serviceKey}에 과거 {@code @NotBlank}가
 * 붙어 있어 실제로 이 문제가 재현됐던 결함 사례 — 이후 수정 완료, {@code StockProperties} 참고).
 * apiKey 미설정 시에도 부팅이 성공해야 한다는 요건(루트 CLAUDE.md 확정 정책)을 지키기 위해, 이
 * 클래스는 검증 애너테이션 없이 빈 문자열을 그대로 허용하고, 실제로 시세를 조회하는 시점
 * ({@link TwelveDataClient})에서만 실패하도록 한다.
 */
@Validated
@ConfigurationProperties(prefix = "allfolio.twelvedata")
public record TwelveDataProperties(
        @NotBlank
        String baseUrl,

        String apiKey
) {
}
