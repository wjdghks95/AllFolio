package com.allfolio.domain.service;

import com.allfolio.domain.Asset;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.Price;
import com.allfolio.domain.PrecisionScale;
import com.allfolio.domain.PricedQuote;
import com.allfolio.domain.exception.AssetNotFoundException;
import com.allfolio.domain.exception.ExternalPriceApiException;
import com.allfolio.domain.exception.PriceRateLimitExceededException;
import com.allfolio.domain.exception.PriceUnavailableException;
import com.allfolio.domain.repository.AssetRepository;
import com.allfolio.infra.cache.PriceCacheProperties;
import com.allfolio.infra.cache.PriceCacheStore;
import com.allfolio.infra.cache.PriceThrottle;
import com.allfolio.infra.price.ExchangeRateClient;
import com.allfolio.infra.price.StockPriceClient;
import com.allfolio.infra.price.UpbitPriceClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 시세 조회 라우팅(docs/ROADMAP.md Task 021) + Redis 캐시·Throttling 오케스트레이션(Task 022).
 * COIN은 업비트, STOCK은 공공데이터포털 주식시세정보, CASH(USD)는 환율 API로 라우팅한다. CASH(KRW)는
 * 시세 조회 대상이 아니다 — avg_price=1 고정값이 이미 평가금액이다. 소유권 검증은 SimulationService와
 * 동일하게 404 ASSET_NOT_FOUND로 통일한다.
 */
@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);

    private final AssetRepository assetRepository;
    private final UpbitPriceClient upbitPriceClient;
    private final StockPriceClient stockPriceClient;
    private final ExchangeRateClient exchangeRateClient;
    private final PriceCacheStore priceCacheStore;
    private final PriceThrottle priceThrottle;
    private final PriceCacheProperties priceCacheProperties;
    private final MeterRegistry meterRegistry;

    public PriceService(AssetRepository assetRepository, UpbitPriceClient upbitPriceClient,
            StockPriceClient stockPriceClient, ExchangeRateClient exchangeRateClient,
            PriceCacheStore priceCacheStore, PriceThrottle priceThrottle,
            PriceCacheProperties priceCacheProperties, MeterRegistry meterRegistry) {
        this.assetRepository = assetRepository;
        this.upbitPriceClient = upbitPriceClient;
        this.stockPriceClient = stockPriceClient;
        this.exchangeRateClient = exchangeRateClient;
        this.priceCacheStore = priceCacheStore;
        this.priceThrottle = priceThrottle;
        this.priceCacheProperties = priceCacheProperties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 트랜잭션을 두르지 않는다 — 소유권 조회(단건 SELECT) 직후 캐시 미스 시 외부 API 호출(최대 connect 2s +
     * read 3s)이 이어지는데, 메서드 레벨 트랜잭션을 쓰면 Hibernate가 트랜잭션 종료까지 DB 커넥션(Hikari)을
     * 점유해 외부 API 장애가 DB 커넥션 풀 고갈로 번질 수 있다.
     *
     * <p>캐시 키가 자산의 ticker/currency에 의존하므로 캐시 조회보다 소유권 조회(자산 엔티티 확보)가
     * 먼저다 — 순수 캐시 키만으로는 자산 유형을 알 수 없어 조회를 건너뛸 수 없다. 캐시가 신선(fresh)하면
     * Throttle을 소모하지 않고 즉시 반환한다(Throttle은 외부 API를 실제로 호출하려는 시도에만 건다).
     *
     * <p>CASH(KRW)는 애초에 시세 조회 대상이 아니라 캐시에 절대 저장되지 않으므로 캐시 조회·Throttle보다
     * 먼저 걸러낸다 — 뒤로 미루면 매번 캐시 미스로 Throttle만 소모하고 400이 나가야 할 요청이 반복
     * 호출 시 429로 바뀌고, 사용자 단위 Throttle이라 다른 정상 자산 조회까지 막히는 문제가 있었다.
     */
    public PricedQuote getPrice(UUID userId, UUID assetId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        // 캐시 히트(즉시 반환)와 외부 API 호출(초 단위 소요)은 소요 시간의 자릿수 자체가 달라, 같은
        // 메트릭에 함께 찍으면 캐시 히트 비율이 늘수록 P99가 실제 외부 API 성능과 무관하게 좋아 보인다.
        // source 태그로 갈라 각자의 분포를 따로 관측한다.
        String source = "external";
        try {
            Asset asset = assetRepository.findByIdAndUser_Id(assetId, userId)
                    .orElseThrow(() -> new AssetNotFoundException("해당 자산을 찾을 수 없습니다."));

            if (asset.getAssetType() == AssetType.CASH && !"USD".equals(asset.getCurrency())) {
                throw new PriceUnavailableException("CASH(KRW) 자산은 시세 조회 대상이 아닙니다.");
            }

            String cacheKey = cacheKeyFor(asset);
            Duration freshTtl = freshTtlFor(asset.getAssetType());
            Optional<PricedQuote> cached = priceCacheStore.find(cacheKey, freshTtl);
            if (cached.isPresent() && !cached.get().stale()) {
                source = "cache";
                return cached.get();
            }

            return resolve(userId, asset, cacheKey, cached, true);
        } finally {
            // 404/400/429/503 등 예외 경로에서도 계측이 빠지면 안 된다 — 특히 외부 API 타임아웃(3초)으로
            // 실패하는 느린 호출이 메트릭에서 통째로 사라지는 것을 방지한다.
            sample.stop(meterRegistry.timer("allfolio.price.fetch.duration", "source", source));
        }
    }

    /**
     * `GET /v1/portfolio`(Task 023) 전용 조회. 소유권 검증은 호출자가 이미 본인 소유 Asset을 들고 있다고
     * 가정해 생략하고, Throttle(사용자당 초당 1건)도 적용하지 않는다 — 사용자가 보유한 자산 수만큼 한 번에
     * 조회해야 하는 포트폴리오 화면 특성상 단건 조회용 Throttle을 그대로 적용하면 자산 2개 이상부터
     * 즉시 걸린다(사용자 확정 정책). CASH(KRW)와 어떤 예외 상황이든(외부 API 장애 포함) 호출자에게
     * 예외를 전파하지 않고 Optional.empty()로 흡수한다 — 자산 하나의 시세 실패가 나머지 자산의 정상
     * 응답을 막으면 안 된다(사용자 확정 정책). 계측(Timer.Sample)은 붙이지 않는다(후속 과제).
     *
     * <p>Throttle이 없는 대신, 실패한 조회는 짧은 TTL로 부정 캐싱한다(Task 023 Major 2) — 존재하지
     * 않는 티커로 등록된 자산이 있으면 GET /v1/portfolio를 반복 호출할 때마다 외부 API가 상한 없이
     * 불리는 문제를 막는다. 단건 조회 API({@link #getPrice})는 이미 Throttle이 있어 대상이 아니다.
     */
    public Optional<PricedQuote> quoteForPortfolio(Asset asset) {
        if (asset.getAssetType() == AssetType.CASH && !"USD".equals(asset.getCurrency())) {
            return Optional.empty();
        }

        String cacheKey = cacheKeyFor(asset);
        if (priceCacheStore.hasRecentFailure(cacheKey)) {
            return Optional.empty();
        }

        try {
            Duration freshTtl = freshTtlFor(asset.getAssetType());
            Optional<PricedQuote> cached = priceCacheStore.find(cacheKey, freshTtl);
            if (cached.isPresent() && !cached.get().stale()) {
                return cached;
            }

            return Optional.of(resolve(null, asset, cacheKey, cached, false));
        } catch (RuntimeException e) {
            log.warn("포트폴리오 시세 조회 실패: assetId={}, ticker={}", asset.getId(), asset.getTicker(), e);
            priceCacheStore.markFailed(cacheKey);
            return Optional.empty();
        }
    }

    /**
     * 캐시 미스/스테일 상태에서 실제 외부 API를 호출해 값을 확보하는 공통 경로. enforceThrottle=true일
     * 때만 Throttle을 소모한다(단건 조회 API 전용 제약 — quoteForPortfolio()는 이 검사를 건너뛴다).
     */
    private PricedQuote resolve(UUID userId, Asset asset, String cacheKey, Optional<PricedQuote> cached,
            boolean enforceThrottle) {
        if (enforceThrottle && !priceThrottle.tryAcquire(userId)) {
            throw new PriceRateLimitExceededException("시세 조회 요청이 너무 잦습니다. 잠시 후 다시 시도하세요.");
        }

        try {
            Price rawPrice = fetchRawPrice(asset);
            Price scaledPrice = scale(rawPrice, asset);
            priceCacheStore.save(cacheKey, scaledPrice);
            return new PricedQuote(scaledPrice, false);
        } catch (ExternalPriceApiException e) {
            if (cached.isPresent()) {
                return new PricedQuote(cached.get().price(), true);
            }
            throw e;
        }
    }

    /** CASH(KRW)는 getPrice() 진입 시점에 이미 걸러지므로 여기 도달하는 CASH는 항상 USD다. */
    private Price fetchRawPrice(Asset asset) {
        return switch (asset.getAssetType()) {
            case COIN -> upbitPriceClient.getPrice(asset.getTicker());
            case STOCK -> stockPriceClient.getPrice(asset.getTicker());
            case CASH -> exchangeRateClient.getUsdKrwRate();
        };
    }

    /**
     * 스케일은 실제 반환 통화(rawPrice.currency()) 기준이어야 한다 — CASH(USD) 자산의 환율 시세는
     * 원화 환산값(currency=KRW)으로 오는데, asset.getCurrency()(USD)로 스케일을 계산하면
     * currency=KRW인데 scale은 USD(4자리)가 되는 자기모순이 생긴다.
     */
    private Price scale(Price rawPrice, Asset asset) {
        int scale = PrecisionScale.scaleFor(asset.getAssetType(), rawPrice.currency());
        return new Price(
                rawPrice.amount().setScale(scale, RoundingMode.HALF_UP),
                rawPrice.currency(),
                rawPrice.asOf());
    }

    /** 캐시 키는 시장 데이터 식별자 기준(사용자 무관) — 같은 종목을 여러 사용자가 조회해도 캐시를 공유한다. */
    private String cacheKeyFor(Asset asset) {
        return switch (asset.getAssetType()) {
            case COIN, STOCK -> "price:%s:%s".formatted(asset.getAssetType(), asset.getTicker());
            case CASH -> "price:CASH:" + asset.getCurrency();
        };
    }

    private Duration freshTtlFor(AssetType assetType) {
        return switch (assetType) {
            case COIN -> priceCacheProperties.coinFreshTtl();
            case STOCK -> priceCacheProperties.stockFreshTtl();
            case CASH -> priceCacheProperties.cashUsdFreshTtl();
        };
    }
}
