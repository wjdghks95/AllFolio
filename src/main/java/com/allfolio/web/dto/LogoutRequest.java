package com.allfolio.web.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /v1/auth/logout 요청 (docs/ROADMAP.md Task 019). */
public record LogoutRequest(@NotBlank String refreshToken) {
}
