package com.allfolio.domain.service;

import com.allfolio.domain.Asset;
import com.allfolio.domain.Price;
import com.allfolio.domain.PrecisionScale;
import com.allfolio.domain.exception.AssetNotFoundException;
import com.allfolio.domain.exception.PriceUnavailableException;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.infra.price.ExchangeRateClient;
import com.allfolio.infra.price.StockPriceClient;
import com.allfolio.infra.price.UpbitPriceClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.util.UUID;

/**
 * 시세 조회 라우팅 (docs/ROADMAP.md Task 021). COIN은 업비트, STOCK은 공공데이터포털 주식시세정보,
 * CASH(USD)는 환율 API로 라우팅한다. CASH(KRW)는 시세 조회 대상이 아니다 — avg_price=1 고정값이
 * 이미 평가금액이다. 소유권 검증은 SimulationService와 동일하게 404 ASSET_NOT_FOUND로 통일한다.
 */
@Service
public class PriceService {

    private final AssetRepository assetRepository;
    private final UpbitPriceClient upbitPriceClient;
    private final StockPriceClient stockPriceClient;
    private final ExchangeRateClient exchangeRateClient;
    private final MeterRegistry meterRegistry;

    public PriceService(AssetRepository assetRepository, UpbitPriceClient upbitPriceClient,
            StockPriceClient stockPriceClient, ExchangeRateClient exchangeRateClient, MeterRegistry meterRegistry) {
        this.assetRepository = assetRepository;
        this.upbitPriceClient = upbitPriceClient;
        this.stockPriceClient = stockPriceClient;
        this.exchangeRateClient = exchangeRateClient;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 트랜잭션을 두르지 않는다 — 소유권 조회(단건 SELECT) 직후 외부 API 호출(최대 connect 2s + read 3s)이
     * 이어지는데, 메서드 레벨 트랜잭션을 쓰면 Hibernate가 트랜잭션 종료까지 DB 커넥션(Hikari)을 점유해
     * 외부 API 장애가 DB 커넥션 풀 고갈로 번질 수 있다. {@code AssetRepository.findByIdAndUser_Id}는
     * Spring Data JPA가 자체 단건 트랜잭션으로 처리하므로 이 메서드에서 별도로 감쌀 필요가 없고,
     * {@link Asset}에서 이후 쓰는 값(assetType/currency/ticker)은 지연 로딩 대상이 아니라 트랜잭션
     * 종료 이후에도 안전하게 접근 가능하다.
     */
    public Price getPrice(UUID userId, UUID assetId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Asset asset = assetRepository.findByIdAndUser_Id(assetId, userId)
                    .orElseThrow(() -> new AssetNotFoundException("해당 자산을 찾을 수 없습니다."));

            Price rawPrice = fetchRawPrice(asset);

            // 스케일은 실제 반환 통화(rawPrice.currency()) 기준이어야 한다 — CASH(USD) 자산의 환율 시세는
            // 원화 환산값(currency=KRW)으로 오는데, asset.getCurrency()(USD)로 스케일을 계산하면
            // currency=KRW인데 scale은 USD(4자리)가 되는 자기모순이 생긴다.
            int scale = PrecisionScale.scaleFor(asset.getAssetType(), rawPrice.currency());
            return new Price(
                    rawPrice.amount().setScale(scale, RoundingMode.HALF_UP),
                    rawPrice.currency(),
                    rawPrice.asOf());
        } finally {
            // 404/400/503 등 예외 경로에서도 계측이 빠지면 안 된다 — 특히 외부 API 타임아웃(3초)으로
            // 실패하는 느린 호출이 메트릭에서 통째로 사라지는 것을 방지한다.
            sample.stop(meterRegistry.timer("allfolio.price.fetch.duration"));
        }
    }

    private Price fetchRawPrice(Asset asset) {
        return switch (asset.getAssetType()) {
            case COIN -> upbitPriceClient.getPrice(asset.getTicker());
            case STOCK -> stockPriceClient.getPrice(asset.getTicker());
            case CASH -> fetchCashPrice(asset);
        };
    }

    private Price fetchCashPrice(Asset asset) {
        if ("USD".equals(asset.getCurrency())) {
            return exchangeRateClient.getUsdKrwRate();
        }
        throw new PriceUnavailableException("CASH(KRW) 자산은 시세 조회 대상이 아닙니다.");
    }
}
