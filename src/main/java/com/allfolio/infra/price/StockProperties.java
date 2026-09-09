package com.allfolio.infra.price;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 공공데이터포털 "금융위원회_주식시세정보" API 설정. serviceKey는 환경변수
 * ALLFOLIO_STOCK_SERVICE_KEY로 주입한다(하드코딩 방지).
 *
 * <p><b>serviceKey에 {@code @NotBlank}를 붙이지 않은 이유</b>: {@code @Validated}가 붙은
 * {@code @ConfigurationProperties} 레코드는 Spring Boot가 바인딩 시점에 Bean Validation을
 * 실제로 수행하며, 필드가 빈 문자열이면 {@code ConfigurationPropertiesBindException}으로
 * 컨텍스트 로딩 자체가 실패한다(과거 결함 실측 — 루트 CLAUDE.md는 "ALLFOLIO_STOCK_SERVICE_KEY
 * 미설정 시에도 부팅은 정상"이라고 명시하는데, {@code @NotBlank}가 붙어 있던 시절에는 이 문서와
 * 달리 실제로 부팅이 실패했다). ALLFOLIO_STOCK_SERVICE_KEY 미설정 시에도 부팅이 성공해야 한다는
 * 이 프로젝트의 확정 정책을 지키기 위해, 이 필드는 검증 애너테이션 없이 빈 문자열을 그대로 허용하고,
 * 실제 실패는 시세를 조회하는 시점({@link StockPriceClient#getPrice(String)})에서만 일어나도록 한다
 * (빈 서비스키로 실제 호출 시 공공데이터포털이 {@code SERVICE_KEY_IS_NULL} 인증 실패 응답을 반환하고,
 * 기존 null 방어 로직이 이를 {@code ExternalPriceApiException}으로 전환한다 — 2026-09-08 실측).
 */
@Validated
@ConfigurationProperties(prefix = "allfolio.stock")
public record StockProperties(
        @NotBlank
        String baseUrl,

        String serviceKey
) {
}
