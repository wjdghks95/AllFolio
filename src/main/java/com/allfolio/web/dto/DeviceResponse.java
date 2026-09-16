package com.allfolio.web.dto;

import com.allfolio.domain.DevicePlatform;

import java.time.Instant;
import java.util.UUID;

/** 토큰 원문은 절대 포함하지 않는다 — 응답/로그에 FCM 토큰이 남지 않도록 하기 위함(docs/ROADMAP.md Task 029). */
public record DeviceResponse(
        UUID id,
        DevicePlatform platform,
        Instant createdAt
) {
}
