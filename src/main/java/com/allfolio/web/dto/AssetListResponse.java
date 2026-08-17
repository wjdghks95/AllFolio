package com.allfolio.web.dto;

import java.util.List;

/**
 * GET /v1/assets 응답. nextCursor는 불투명 문자열로 계약한다 — 실제로는 마지막 항목의 UUID지만
 * 클라이언트가 해석하지 않기로 하면 정렬 기준이 바뀌어도 프론트를 고치지 않아도 된다
 * (docs/ROADMAP.md Task 006). 마지막 페이지면 null.
 */
public record AssetListResponse(List<AssetResponse> items, String nextCursor) {
}
