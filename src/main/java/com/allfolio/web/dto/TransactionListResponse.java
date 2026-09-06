package com.allfolio.web.dto;

import java.util.List;

/**
 * GET /v1/assets/{id}/transactions 응답. nextCursor는 불투명 문자열("<tradedAt epochMillis>_<uuid>")
 * 이며 마지막 페이지면 null (AssetListResponse와 동일한 커서 페이지네이션 계약, docs/ROADMAP.md Task 024).
 */
public record TransactionListResponse(List<TransactionItemResponse> items, String nextCursor) {
}
