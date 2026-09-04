package com.allfolio.web.dto;

import com.allfolio.domain.AssetType;

import java.util.UUID;

/**
 * GET /v1/portfolio의 자산별 항목. evaluationKrw/unrealizedPnl/weight는 외부 시세·환율을 반영한
 * KRW 환산값이다(docs/ROADMAP.md Task 023, domain/service/PortfolioService 참고). 해당 자산의
 * 시세 조회가 실패하면(부분 실패 허용 정책) 이 세 필드만 null로 남고 나머지 자산·응답은 정상이다 —
 * 거짓 숫자를 내보내지 않기 위해 실패 시에도 필드 자체는 계약에 그대로 유지한다.
 *
 * <p>weight가 BigDecimal이 아닌 String인 이유: 금액·수량·비중 등 모든 금융성 수치는 JSON에서
 * 문자열로 직렬화하는 계약이다(docs/ROADMAP.md Task 006) — BigDecimal로 두면 Jackson이 JSON
 * 숫자로 내보내 계약이 깨진다.
 */
public record PortfolioItemResponse(
        UUID assetId,
        String ticker,
        String name,
        AssetType assetType,
        String currency,
        String quantity,
        String avgPrice,
        String cost,
        String evaluationKrw,
        String unrealizedPnl,
        String weight
) {
}
