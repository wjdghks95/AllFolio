package com.allfolio.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param password BCrypt는 72바이트를 초과하는 입력에 IllegalArgumentException을 던지므로 UTF-8 바이트
 *                 기준으로 막는다(문자 수 기준 Size는 멀티바이트 입력을 못 막아 MaxUtf8Bytes로 검증한다).
 */
public record SignupRequest(

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(min = 8)
        @MaxUtf8Bytes(value = 72, message = "비밀번호는 72바이트를 초과할 수 없습니다.")
        String password
) {
}
