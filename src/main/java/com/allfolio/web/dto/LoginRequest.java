package com.allfolio.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로그인 요청.
 * password에 길이 제약을 걸지 않는다 — 정책 변경 전 가입자를 차단하지 않기 위함이고,
 * 400과 401의 차이로 비밀번호 정책이 유추되는 것도 막는다.
 */
public record LoginRequest(

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotBlank
        String password
) {
}
