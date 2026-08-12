package com.allfolio.web.dto;

import java.time.Instant;

/** 에러 응답 포맷 — {code, message, timestamp} 3필드 고정 (docs/ROADMAP.md Task 003 「에러 응답 포맷」). */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now());
    }
}
