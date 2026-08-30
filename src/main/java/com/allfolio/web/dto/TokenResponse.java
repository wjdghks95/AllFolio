package com.allfolio.web.dto;

/**
 * Access Token + Refresh Token 응답. 쿠키가 아닌 JSON body로만 전달한다 (하이브리드 앱 대응, docs/ROADMAP.md Task 003·019).
 *
 * @param refreshToken 원문 Refresh Token(DB에는 해시만 저장, docs/ROADMAP.md Task 019)
 * @param expiresIn Access Token 만료까지 남은 초
 */
public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {

    public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
