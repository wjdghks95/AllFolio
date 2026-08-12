package com.allfolio.web.dto;

/**
 * Access Token 응답. 쿠키가 아닌 JSON body로만 전달한다 (하이브리드 앱 대응, docs/ROADMAP.md Task 003).
 *
 * @param expiresIn 만료까지 남은 초
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

    public static TokenResponse bearer(String accessToken, long expiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn);
    }
}
