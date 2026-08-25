package com.allfolio.domain.service;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Holding;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.domain.repository.HoldingRepository;
import com.allfolio.web.dto.PortfolioItemResponse;
import com.allfolio.web.dto.PortfolioResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 포트폴리오 홈 집계 (docs/ROADMAP.md Task 013). AssetService(CRUD)와 책임을 분리해둔다 —
 * Task 023(외부 시세 연동)이 evaluationKrw/unrealizedPnl/weight를 채울 때 이 클래스만
 * 건드리면 되도록 경계를 미리 나눈다.
 */
@Service
public class PortfolioService {

    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;

    public PortfolioService(AssetRepository assetRepository, HoldingRepository holdingRepository) {
        this.assetRepository = assetRepository;
        this.holdingRepository = holdingRepository;
    }

    /** evaluationKrw/unrealizedPnl/weight/totalEvaluationKrw/totalUnrealizedPnl은 외부 시세 연동 전까지 항상 null(Task 023). */
    @Transactional(readOnly = true)
    public PortfolioResponse listPortfolio(UUID userId) {
        List<Asset> assets = assetRepository.findByUser_IdOrderByIdDesc(userId);

        List<UUID> assetIds = assets.stream().map(Asset::getId).toList();
        Map<UUID, Holding> holdingsByAssetId = holdingRepository.findByAsset_IdIn(assetIds).stream()
                .collect(Collectors.toMap(h -> h.getAsset().getId(), Function.identity()));

        List<PortfolioItemResponse> items = assets.stream()
                .map(asset -> toItemResponse(asset, holdingsByAssetId.get(asset.getId())))
                .toList();

        return new PortfolioResponse(items, totalCostByCurrency(items), null, null);
    }

    /**
     * holding은 Asset과 단일 트랜잭션에서 함께 생성되므로(docs/ROADMAP.md Task 012) 항상
     * non-null이다 — 방어적 null 체크를 두지 않는다(CLAUDE.md 「불가능한 시나리오 에러 처리 금지」).
     */
    private PortfolioItemResponse toItemResponse(Asset asset, Holding holding) {
        int scale = scaleFor(asset);
        BigDecimal cost = holding.getQuantity().multiply(holding.getAvgPrice())
                .setScale(scale, RoundingMode.HALF_UP);

        return new PortfolioItemResponse(
                asset.getId(),
                asset.getTicker(),
                asset.getName(),
                asset.getAssetType(),
                asset.getCurrency(),
                // quantity는 자산유형·통화 무관 항상 8자리(시뮬레이터 expectedQuantity와 동일 규칙).
                holding.getQuantity().setScale(8, RoundingMode.HALF_UP).toPlainString(),
                holding.getAvgPrice().setScale(scale, RoundingMode.HALF_UP).toPlainString(),
                cost.toPlainString(),
                null,
                null,
                null);
    }

    /** 「금융 정밀도 규칙」(docs/ROADMAP.md): COIN은 통화 무관 8자리, 그 외엔 통화 기준. cost·avgPrice가 공유한다. */
    private int scaleFor(Asset asset) {
        if (asset.getAssetType() == AssetType.COIN) {
            return 8;
        }
        return scaleForCurrency(asset.getCurrency());
    }

    private int scaleForCurrency(String currency) {
        return switch (currency) {
            case "KRW" -> 0;
            case "USD" -> 4;
            default -> 2;
        };
    }

    /** currency별 cost 합산 후 그 통화의 스케일(scaleForCurrency)로 반올림한다 — 항목별 cost와 표기가 어긋나지 않도록. */
    private Map<String, String> totalCostByCurrency(List<PortfolioItemResponse> items) {
        return items.stream()
                .collect(Collectors.groupingBy(PortfolioItemResponse::currency))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> sumCost(e.getKey(), e.getValue())));
    }

    private String sumCost(String currency, List<PortfolioItemResponse> itemsInCurrency) {
        BigDecimal sum = itemsInCurrency.stream()
                .map(item -> new BigDecimal(item.cost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.setScale(scaleForCurrency(currency), RoundingMode.HALF_UP).toPlainString();
    }
}
