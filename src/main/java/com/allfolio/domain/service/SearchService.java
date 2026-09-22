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
import java.util.Locale;
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
     * 통화 유효성 검증은 캐시 조회보다 먼저 실행한다 — COIN은 통화와 무관하게 단일 캐시 키
     * (search:COIN:ALL)를 쓰므로, 검증을 route() 안에만 두면 캐시 히트 시 검증이 우회되어
     * 동일 요청의 응답 코드가 캐시 상태에 따라 400/200으로 비결정적으로 바뀌는 문제가 있었다.
     */
    public List<SearchResult> search(UUID userId, AssetType assetType, String currency, String q) {
        validateCurrency(assetType, currency);  // 1. 캐시 히트/미스와 무관하게 항상 먼저 검증

        String cacheKey = buildCacheKey(assetType, currency, q);

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
     * assetType·currency 조합을 외부 클라이언트로 라우팅한다. validateCurrency()가 search()
     * 진입 시 이미 조합을 검증했으므로 여기서는 통화 유효성을 다시 검사하지 않는다.
     * - STOCK+KRW: 공공데이터포털(StockPriceClient)
     * - STOCK+USD: Twelve Data(TwelveDataClient)
     * - COIN(통화 무관): 업비트 전체 마켓 목록(UpbitPriceClient.listMarkets)
     */
    private List<SearchResult> route(AssetType assetType, String currency, String q) {
        return switch (assetType) {
            case STOCK -> switch (currency) {
                case "KRW" -> stockPriceClient.search(q);
                case "USD" -> twelveDataClient.search(q);
                default -> throw new IllegalStateException(
                        "validateCurrency()에서 걸러진 통화만 도달해야 함: " + currency);
            };
            case COIN -> upbitPriceClient.listMarkets();
            case CASH -> throw new SearchValidationException(
                    "CASH 자산 유형은 종목 검색 대상이 아닙니다.");
        };
    }

    /**
     * assetType·currency 조합의 유효성을 검증한다. search() 진입 직후, 캐시 키 생성/조회보다
     * 먼저 호출되어야 한다 — 그래야 캐시 히트 시에도 검증이 우회되지 않는다.
     * - CASH: 종목 검색 대상이 아님
     * - STOCK/COIN: KRW·USD만 지원
     */
    private void validateCurrency(AssetType assetType, String currency) {
        switch (assetType) {
            case CASH -> throw new SearchValidationException(
                    "CASH 자산 유형은 종목 검색 대상이 아닙니다.");
            case STOCK -> {
                if (!"KRW".equals(currency) && !"USD".equals(currency)) {
                    throw new SearchValidationException("STOCK 자산에 지원하지 않는 통화입니다: " + currency);
                }
            }
            case COIN -> {
                if (!"KRW".equals(currency) && !"USD".equals(currency)) {
                    throw new SearchValidationException("COIN 자산에 지원하지 않는 통화입니다: " + currency);
                }
            }
        }
    }

    /**
     * [Major] currency 필터 후 ticker/name 검색어 필터를 적용한다.
     * 업비트 listMarkets()는 KRW·USD 마켓을 혼합해 반환하므로 currency 일치 여부를 먼저 확인한다.
     */
    private List<SearchResult> filterByQuery(List<SearchResult> results, String currency, String q) {
        String lowerQ = q.toLowerCase(Locale.ROOT);
        return results.stream()
                .filter(r -> r.currency().equalsIgnoreCase(currency))
                .filter(r -> r.ticker().toLowerCase(Locale.ROOT).contains(lowerQ)
                        || r.name().toLowerCase(Locale.ROOT).contains(lowerQ))
                .toList();
    }
}
