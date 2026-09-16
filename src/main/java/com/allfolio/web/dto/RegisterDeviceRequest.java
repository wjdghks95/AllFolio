package com.allfolio.web.dto;

import com.allfolio.domain.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** POST /v1/devices 요청 본문 (docs/ROADMAP.md Task 029). */
public record RegisterDeviceRequest(
        @NotBlank
        @Size(max = 4096)
        String token,

        @NotNull
        DevicePlatform platform
) {
}
