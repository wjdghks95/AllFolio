package com.allfolio.domain.service;

import com.allfolio.domain.AssetType;
import com.allfolio.domain.SearchResult;
import com.allfolio.domain.exception.SearchRateLimitExceededException;
import com.allfolio.domain.exception.SearchValidationException;
import com.allfolio.infra.cache.SearchCacheStore;
import com.allfolio.infra.cache.SearchThrottle;
import com.allfolio.infra.price.StockPriceClient;
import com.allfolio.infra.price.TwelveDataClient;
import com.allfolio.infra.price.UpbitPriceClient;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 통합 종목 검색 오케스트레이션(docs/ROADMAP.md Task 026). assetType·currency에 따라 외부 클라이언트를
 * 라우팅하고, SearchCacheStore로 캐시·SearchThrottle로 요청 제한을 적용한다.
 * COIN은 검색어와 무관하게 업비트 전체 마켓 목록을 캐시하고, 캐시 히트 후 인-메모리에서 필터링한다.
 * ExternalPriceApiException은 잡지 않고 그대로 전파 — GlobalExceptionHandler가 503으로 매핑한다.
 */
@Service
public class SearchService {

    private final SearchThrottle searchThrottle;
    private final SearchCacheStore searchCacheStore;
    private final StockPriceClient stockPriceClient;
    private final TwelveDataClient twelveDataClient;
    private final UpbitPriceClient upbitPriceClient;

    public SearchService(SearchThrottle searchThrottle, SearchCacheStore searchCacheStore,
            StockPriceClient stockPriceClient, TwelveDataClient twelveDataClient,
            UpbitPriceClient upbitPriceClient) {
        this.searchThrottle = searchThrottle;
        this.searchCacheStore = searchCacheStore;
        this.stockPriceClient = stockPriceClient;
        this.twelveDataClient = twelveDataClient;
        this.upbitPriceClient = upbitPriceClient;
    }

    /**
     * 종목 검색. Throttle 초과 시 SearchRateLimitExceededException, 지원하지 않는 assetType/currency
     * 조합은 SearchValidationException(400 VALIDATION_ERROR로 매핑)을 던진다.
     */
    public List<SearchResult> search(UUID userId, AssetType assetType, String currency, String q) {
        String cacheKey = buildCacheKey(assetType, currency, q);  // 1. 검증+키 생성 (CASH → 즉시 예외)

        Optional<List<SearchResult>> cached = searchCacheStore.find(cacheKey);
        if (cached.isPresent()) {                                  // 2. 캐시 히트 → Throttle 소모 없이 반환
            return assetType == AssetType.COIN ? filterByQuery(cached.get(), currency, q) : cached.get();
        }

        if (!searchThrottle.tryAcquire(userId)) {                 // 3. 캐시 미스일 때만 Throttle 소모
            throw new SearchRateLimitExceededException("종목 검색 요청 한도를 초과했습니다.");
        }

        List<SearchResult> results = route(assetType, currency, q);

        // [Minor 1] 공통 저장으로 합침 — COIN·non-COIN 모두 동일한 save 호출
        searchCacheStore.save(cacheKey, results);

        if (assetType == AssetType.COIN) {
            return filterByQuery(results, currency, q);
        }
        return results;
    }

    /**
     * 캐시 키 생성 규칙:
     * - STOCK: {@code search:STOCK:{currency}:{q}} — 검색어와 통화별로 독립적으로 캐싱
     * - COIN: {@code search:COIN:ALL} — 업비트 전체 마켓 목록을 단일 키로 캐싱하고 q는 인-메모리 필터링
     * - CASH 등 지원하지 않는 타입: SearchValidationException(→ 400)
     */
    private String buildCacheKey(AssetType assetType, String currency, String q) {
        return switch (assetType) {
            case STOCK -> "search:STOCK:" + currency + ":" + URLEncoder.encode(q, StandardCharsets.UTF_8);
            case COIN -> "search:COIN:ALL";
            case CASH -> throw new SearchValidationException(
                    "CASH 자산 유형은 종목 검색 대상이 아닙니다.");
        };
    }

    /**
     * assetType·currency 조합을 외부 클라이언트로 라우팅한다.
     * - STOCK+KRW: 공공데이터포털(StockPriceClient)
     * - STOCK+USD: Twelve Data(TwelveDataClient)
     * - COIN(통화 무관): 업비트 전체 마켓 목록(UpbitPriceClient.listMarkets)
     * 그 외 조합은 SearchValidationException(→ 400 VALIDATION_ERROR).
     */
    private List<SearchResult> route(AssetType assetType, String currency, String q) {
        return switch (assetType) {
            case STOCK -> switch (currency) {
                case "KRW" -> stockPriceClient.search(q);
                case "USD" -> twelveDataClient.search(q);
                default -> throw new SearchValidationException(
                        "STOCK 자산에 지원하지 않는 통화입니다: " + currency);
            };
            case COIN -> {
                if (!"KRW".equals(currency) && !"USD".equals(currency)) {
                    throw new SearchValidationException("COIN 자산에 지원하지 않는 통화입니다: " + currency);
                }
                yield upbitPriceClient.listMarkets();
            }
            case CASH -> throw new SearchValidationException(
                    "CASH 자산 유형은 종목 검색 대상이 아닙니다.");
        };
    }

    /**
     * [Major] currency 필터 후 ticker/name 검색어 필터를 적용한다.
     * 업비트 listMarkets()는 KRW·USD 마켓을 혼합해 반환하므로 currency 일치 여부를 먼저 확인한다.
     */
    private List<SearchResult> filterByQuery(List<SearchResult> results, String currency, String q) {
        String lowerQ = q.toLowerCase();
        return results.stream()
                .filter(r -> r.currency().equalsIgnoreCase(currency))
                .filter(r -> r.ticker().toLowerCase().contains(lowerQ)
                        || r.name().toLowerCase().contains(lowerQ))
                .toList();
    }
}
