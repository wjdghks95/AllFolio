package com.allfolio.domain.service;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Holding;
import com.allfolio.domain.exception.AssetNotFoundException;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.domain.repository.HoldingRepository;
import com.allfolio.web.dto.SimulateAvgPriceRequest;
import com.allfolio.web.dto.SimulateAvgPriceResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * 물타기 시뮬레이터 (docs/ROADMAP.md Task 015, F006). DB 쓰기 없음 — holding을 단건 조회한
 * 뒤 In-Memory에서 가중평균만 계산한다. 소유권 검증은 AssetService와 동일하게 404
 * ASSET_NOT_FOUND로 통일한다(403이면 "그 ID가 존재한다"는 사실이 새어 나간다).
 */
@Service
public class SimulationService {

    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;
    private final MeterRegistry meterRegistry;

    public SimulationService(AssetRepository assetRepository, HoldingRepository holdingRepository,
            MeterRegistry meterRegistry) {
        this.assetRepository = assetRepository;
        this.holdingRepository = holdingRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * P99 KPI(docs/ROADMAP.md 「성능 KPI」, ≤5ms)는 이 조회+계산 구간을 대상으로 한다.
     * 자산을 못 찾는 실패 경로는 측정하지 않는다 — KPI가 정상 계산 시나리오를 전제로 하기 때문이다.
     */
    @Transactional(readOnly = true)
    public SimulateAvgPriceResponse simulate(UUID userId, SimulateAvgPriceRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);

        Asset asset = assetRepository.findByIdAndUser_Id(request.assetId(), userId)
                .orElseThrow(() -> new AssetNotFoundException("해당 자산을 찾을 수 없습니다."));
        Holding holding = findHolding(asset.getId());

        BigDecimal currentQuantity = holding.getQuantity();
        BigDecimal currentAvgPrice = holding.getAvgPrice();
        BigDecimal additionalQuantity = request.additionalQuantity();
        BigDecimal additionalPrice = request.additionalPrice();

        BigDecimal totalQuantity = currentQuantity.add(additionalQuantity);
        BigDecimal totalCost = currentQuantity.multiply(currentAvgPrice)
                .add(additionalQuantity.multiply(additionalPrice));

        int scale = scaleFor(asset);
        // DB NUMERIC(28,8) 왕복 scale이 그대로 노출되지 않도록, 응답에 나가기 전 두 평단가 모두
        // 여기서 최종 반올림한다(Task 012/013에서 실제로 있었던 결함 유형).
        BigDecimal expectedAvgPrice = totalCost.divide(totalQuantity, scale, RoundingMode.HALF_UP);
        BigDecimal currentAvgPriceFormatted = currentAvgPrice.setScale(scale, RoundingMode.HALF_UP);

        SimulateAvgPriceResponse response =
                SimulateAvgPriceResponse.of(currentAvgPriceFormatted, expectedAvgPrice, totalQuantity);

        sample.stop(meterRegistry.timer("allfolio.simulation.duration"));
        return response;
    }

    /** 자산 생성 시 Holding이 항상 함께 만들어지므로(불변식) 비어있는 경우는 데이터 정합성 오류다. */
    private Holding findHolding(UUID assetId) {
        return holdingRepository.findByAsset_Id(assetId)
                .orElseThrow(() -> new IllegalStateException("자산 " + assetId + "에 대한 보유 정보가 없습니다."));
    }

    /** 「금융 정밀도 규칙」(docs/ROADMAP.md): COIN은 통화 무관 8자리, 그 외엔 통화 기준. */
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
}
