package com.allfolio.domain.service;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Holding;
import com.allfolio.domain.PrecisionScale;
import com.allfolio.domain.PricedQuote;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.domain.repository.HoldingRepository;
import com.allfolio.web.dto.PortfolioItemResponse;
import com.allfolio.web.dto.PortfolioResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 포트폴리오 홈 집계 (docs/ROADMAP.md Task 013, Task 023). AssetService(CRUD)와 책임을 분리해둔다.
 *
 * <p>DB 조회와 외부 시세 조회(PriceService.quoteForPortfolio())를 두 단계로 분리한다 — 후자는
 * 의도적으로 트랜잭션을 두르지 않는 설계라(외부 API 호출 중 DB 커넥션 점유 방지), DB 조회 트랜잭션이
 * 먼저 끝난 뒤에 순회 호출해야 한다. {@code @Transactional}을 이 클래스의 private 메서드에 붙이는
 * 방식은 Spring 프록시의 self-invocation 한계로 조용히 무시되므로(같은 빈 내부 호출은 AOP 프록시를
 * 거치지 않는다), {@link TransactionTemplate}으로 DB 조회 구간만 명시적으로 감싼다.
 */
@Service
public class PortfolioService {

    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;
    private final PriceService priceService;
    private final TransactionTemplate readOnlyTransactionTemplate;

    public PortfolioService(AssetRepository assetRepository, HoldingRepository holdingRepository,
            PriceService priceService, PlatformTransactionManager transactionManager) {
        this.assetRepository = assetRepository;
        this.holdingRepository = holdingRepository;
        this.priceService = priceService;
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    /**
     * 자산 하나의 시세 조회가 실패해도 200을 반환한다 — 실패한 자산만 evaluationKrw/unrealizedPnl/weight가
     * null로 남고 나머지는 정상 계산된다. totalEvaluationKrw/totalUnrealizedPnl과 각 자산의 weight(분모)는
     * 시세 조회에 성공한 자산들만으로 계산한다(사용자 확정 정책, Task 023).
     */
    public PortfolioResponse listPortfolio(UUID userId) {
        List<ItemDraft> drafts = readOnlyTransactionTemplate.execute(status -> loadDrafts(userId));

        List<PortfolioItemResponse> pricedItems = drafts.stream().map(this::priceItem).toList();

        BigDecimal totalEvaluation = sumNonNull(pricedItems, PortfolioItemResponse::evaluationKrw);
        BigDecimal totalUnrealizedPnl = sumNonNull(pricedItems, PortfolioItemResponse::unrealizedPnl);

        List<PortfolioItemResponse> items = pricedItems.stream()
                .map(item -> withWeight(item, totalEvaluation))
                .toList();

        return new PortfolioResponse(
                items,
                totalCostByCurrency(items),
                totalEvaluation == null ? null : totalEvaluation.toPlainString(),
                totalUnrealizedPnl == null ? null : totalUnrealizedPnl.toPlainString());
    }

    /** DB 조회 전용 — 트랜잭션 경계 안에서만 호출된다({@link #listPortfolio}의 TransactionTemplate 블록). */
    private List<ItemDraft> loadDrafts(UUID userId) {
        List<Asset> assets = assetRepository.findByUser_IdOrderByIdDesc(userId);

        List<UUID> assetIds = assets.stream().map(Asset::getId).toList();
        Map<UUID, Holding> holdingsByAssetId = holdingRepository.findByAsset_IdIn(assetIds).stream()
                .collect(Collectors.toMap(h -> h.getAsset().getId(), Function.identity()));

        return assets.stream()
                .map(asset -> toDraft(asset, holdingsByAssetId.get(asset.getId())))
                .toList();
    }

    /**
     * holding은 Asset과 단일 트랜잭션에서 함께 생성되므로(docs/ROADMAP.md Task 012) 항상
     * non-null이다 — 방어적 null 체크를 두지 않는다(CLAUDE.md 「불가능한 시나리오 에러 처리 금지」).
     */
    private ItemDraft toDraft(Asset asset, Holding holding) {
        int scale = PrecisionScale.scaleFor(asset.getAssetType(), asset.getCurrency());
        BigDecimal cost = holding.getQuantity().multiply(holding.getAvgPrice())
                .setScale(scale, RoundingMode.HALF_UP);
        BigDecimal avgPrice = holding.getAvgPrice().setScale(scale, RoundingMode.HALF_UP);

        return new ItemDraft(asset, holding.getQuantity(), avgPrice, cost);
    }

    /**
     * CASH(KRW)는 시세 조회 대상이 아니라 quantity 자체가 이미 평가금액이다. STOCK/COIN/CASH(USD)는
     * PriceService.quoteForPortfolio()가 반환하는 원화 환산 금액을 그대로 곱해 평가금액을 낸다 —
     * CASH(USD)만 cost도 같은 환율로 재환산해야 원화 기준 손익이 나온다(cost는 USD 스케일로 저장돼 있음).
     * 시세 조회가 실패하면(Optional.empty) evaluationKrw/unrealizedPnl 둘 다 null로 남긴다.
     */
    private PortfolioItemResponse priceItem(ItemDraft draft) {
        Asset asset = draft.asset();
        BigDecimal evaluationKrw = null;
        BigDecimal unrealizedPnl = null;

        if (asset.getAssetType() == AssetType.CASH && "KRW".equals(asset.getCurrency())) {
            evaluationKrw = draft.quantity().setScale(0, RoundingMode.HALF_UP);
            unrealizedPnl = evaluationKrw.subtract(draft.cost());
        } else {
            Optional<PricedQuote> quote = priceService.quoteForPortfolio(asset);
            if (quote.isPresent()) {
                BigDecimal krwAmount = quote.get().price().amount();
                evaluationKrw = draft.quantity().multiply(krwAmount).setScale(0, RoundingMode.HALF_UP);
                if (asset.getAssetType() == AssetType.CASH) {
                    BigDecimal costKrw = draft.cost().multiply(krwAmount).setScale(0, RoundingMode.HALF_UP);
                    unrealizedPnl = evaluationKrw.subtract(costKrw);
                } else if ("KRW".equals(asset.getCurrency())) {
                    // STOCK/COIN 시세는 항상 KRW 환산액이라(업비트 KRW 마켓, 국내 시세), 자산 통화가
                    // KRW가 아니면(cost가 다른 통화 스케일로 저장돼 있어) 손익을 신뢰성 있게 계산할 수
                    // 없다 — null로 남긴다(Major 3, 거짓 숫자를 내보내지 않는다는 원칙).
                    unrealizedPnl = evaluationKrw.subtract(draft.cost());
                }
            }
        }

        // evaluationKrw는 항상 스케일 0이지만 draft.cost()는 자산 유형/통화별로 스케일이 다를 수 있어
        // (예: COIN=8), subtract() 결과 스케일이 더 큰 피연산자를 따라간다. unrealizedPnl은 어느
        // 분기에서 계산되든 항상 KRW 정수 스케일이어야 하므로 여기서 한 번에 정규화한다(Major 1).
        if (unrealizedPnl != null) {
            unrealizedPnl = unrealizedPnl.setScale(0, RoundingMode.HALF_UP);
        }

        return new PortfolioItemResponse(
                asset.getId(),
                asset.getTicker(),
                asset.getName(),
                asset.getAssetType(),
                asset.getCurrency(),
                // quantity는 자산유형·통화 무관 항상 8자리(시뮬레이터 expectedQuantity와 동일 규칙).
                draft.quantity().setScale(PrecisionScale.QUANTITY_SCALE, RoundingMode.HALF_UP).toPlainString(),
                draft.avgPrice().toPlainString(),
                draft.cost().toPlainString(),
                evaluationKrw == null ? null : evaluationKrw.toPlainString(),
                unrealizedPnl == null ? null : unrealizedPnl.toPlainString(),
                null);
    }

    /** weight는 전체 합계(totalEvaluation)가 확정된 뒤에야 계산할 수 있어 priceItem()과 분리한다. */
    private PortfolioItemResponse withWeight(PortfolioItemResponse item, BigDecimal totalEvaluation) {
        String weight = null;
        if (item.evaluationKrw() != null && totalEvaluation != null
                && totalEvaluation.compareTo(BigDecimal.ZERO) != 0) {
            weight = new BigDecimal(item.evaluationKrw())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalEvaluation, PrecisionScale.WEIGHT_SCALE, RoundingMode.HALF_UP)
                    .toPlainString();
        }

        return new PortfolioItemResponse(
                item.assetId(), item.ticker(), item.name(), item.assetType(), item.currency(),
                item.quantity(), item.avgPrice(), item.cost(),
                item.evaluationKrw(), item.unrealizedPnl(), weight);
    }

    /** 시세 조회에 성공한(non-null) 항목만 합산한다 — 성공한 항목이 하나도 없으면 null. */
    private BigDecimal sumNonNull(List<PortfolioItemResponse> items, Function<PortfolioItemResponse, String> field) {
        List<BigDecimal> values = items.stream()
                .map(field)
                .filter(Objects::nonNull)
                .map(BigDecimal::new)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(0, RoundingMode.HALF_UP);
    }

    /** currency별 cost 합산 후 그 통화의 스케일(PrecisionScale.scaleForCurrency)로 반올림한다 — 항목별 cost와 표기가 어긋나지 않도록. */
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
        return sum.setScale(PrecisionScale.scaleForCurrency(currency), RoundingMode.HALF_UP).toPlainString();
    }

    /** DB 조회 트랜잭션 안에서 확보한, 가격 미반영 중간 상태. asset은 시세 조회(트랜잭션 밖)에서 재사용된다. */
    private record ItemDraft(Asset asset, BigDecimal quantity, BigDecimal avgPrice, BigDecimal cost) {
    }
}
