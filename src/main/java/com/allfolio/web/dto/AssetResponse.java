package com.allfolio.web.dto;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Holding;
import com.allfolio.domain.PrecisionScale;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * 엔티티→DTO 값 변환은 AssetService가 담당한다(open-in-view: false 환경에서 트랜잭션 밖 지연
 * 로딩을 피하기 위함, docs/ROADMAP.md Task 012). 이 레코드는 순수 값만 받는다.
 */
public record AssetResponse(
        UUID id,
        String ticker,
        String name,
        AssetType assetType,
        String currency,
        String quantity,
        String avgPrice,
        int version,
        Instant updatedAt
) {

    /**
     * AssetService·TransactionService가 공유하는 엔티티→DTO 매핑(code-reviewer Major 2, 중복 매핑
     * 로직 제거).
     *
     * <p>holdings.avg_price는 의도적으로 요청받은 원본 정밀도 그대로 NUMERIC(28,8)에 저장된다
     * (반올림하지 않음) — {@link com.allfolio.domain.service.PortfolioService#toDraft}의
     * {@code cost = quantity × avgPrice}가 이 원본 정밀도로 곱셈한 뒤에야 반올림해야 하기 때문이다
     * (docs/ROADMAP.md 「GET /v1/portfolio 응답 예시」 절 「주의」, 사용자 확정 결정). 그래서 이 응답
     * DTO는 "표시 시점"에만 통화·자산유형별 스케일(quantity 8자리 고정, avgPrice는 PrecisionScale
     * 기준)로 반올림한다 — 저장 자체를 반올림하면 이 raw-precision cost 계산이 깨진다.
     *
     * <p>이 표시 시점 반올림 때문에, avgPrice에 스케일보다 작은 단위의 소수(예: KRW 자산에 소수점)가
     * 있으면 POST/GET 응답과 실제 저장값이 다를 수 있고, 그 응답을 그대로 PUT에 되돌리면 원본 소수점
     * 정밀도가 새 저장값으로 대체된다(PUT은 avgPrice를 항상 요청값으로 완전히 교체하는 「set」
     * 시맨틱이다, UpdateHoldingRequest 참고) — 이는 위에서 설명한 raw-precision cost 설계와 짝을
     * 이루는 문서화된 트레이드오프이며 버그가 아니다.
     */
    public static AssetResponse of(Asset asset, Holding holding) {
        int avgPriceScale = PrecisionScale.scaleFor(asset.getAssetType(), asset.getCurrency());
        return new AssetResponse(
                asset.getId(),
                asset.getTicker(),
                asset.getName(),
                asset.getAssetType(),
                asset.getCurrency(),
                holding.getQuantity().setScale(PrecisionScale.QUANTITY_SCALE, RoundingMode.HALF_UP).toPlainString(),
                holding.getAvgPrice().setScale(avgPriceScale, RoundingMode.HALF_UP).toPlainString(),
                holding.getVersion(),
                holding.getUpdatedAt());
    }
}
