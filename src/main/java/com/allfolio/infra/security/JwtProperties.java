package com.allfolio.infra.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT 설정. 시크릿은 환경변수 ALLFOLIO_JWT_SECRET로 주입한다 (docs/ROADMAP.md Task 003).
 *
 * @param secret          HS256 대칭키. RFC 7518 §3.2에 따라 해시 출력 길이(256bit = 32바이트) 이상이어야 한다.
 * @param accessTokenTtl  Access Token 유효기간 (Task 003 기준 15분)
 * @param refreshTokenTtl Refresh Token 유효기간 (Task 019 기준 14일)
 */
@Validated
@ConfigurationProperties(prefix = "allfolio.jwt")
public record JwtProperties(

        @NotBlank
        @Size(min = 32, message = "HS256 시크릿은 최소 32바이트여야 합니다.")
        String secret,

        @NotNull
        Duration accessTokenTtl,

        @NotNull
        Duration refreshTokenTtl
) {
}
