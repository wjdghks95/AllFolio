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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SearchService의 라우팅·캐시·Throttle 오케스트레이션을 검증하는 순수 단위 테스트(Task 026).
 * 외부 API·Redis는 모두 Mock으로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class
SearchServiceTest {

    @Mock
    private SearchThrottle searchThrottle;

    @Mock
    private SearchCacheStore searchCacheStore;

    @Mock
    private StockPriceClient stockPriceClient;

    @Mock
    private TwelveDataClient twelveDataClient;

    @Mock
    private UpbitPriceClient upbitPriceClient;

    private SearchService searchService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        searchService = new SearchService(searchThrottle, searchCacheStore,
                stockPriceClient, twelveDataClient, upbitPriceClient);
    }

    @Test
    void stockKrwRoutesToStockPriceClient() {
        when(searchThrottle.tryAcquire(userId)).thenReturn(true);
        when(searchCacheStore.find(anyString())).thenReturn(Optional.empty());
        List<SearchResult> expected = List.of(new SearchResult("005930", "삼성전자", AssetType.STOCK, "KRW"));
        when(stockPriceClient.search("삼성")).thenReturn(expected);

        List<SearchResult> result = searchService.search(userId, AssetType.STOCK, "KRW", "삼성");

        assertThat(result).isEqualTo(expected);
        verify(stockPriceClient).search("삼성");
        verifyNoInteractions(twelveDataClient, upbitPriceClient);
    }

    @Test
    void stockUsdRoutesToTwelveDataClient() {
        when(searchThrottle.tryAcquire(userId)).thenReturn(true);
        when(searchCacheStore.find(anyString())).thenReturn(Optional.empty());
        List<SearchResult> expected = List.of(new SearchResult("AAPL", "Apple Inc", AssetType.STOCK, "USD"));
        when(twelveDataClient.search("AAPL")).thenReturn(expected);

        List<SearchResult> result = searchService.search(userId, AssetType.STOCK, "USD", "AAPL");

        assertThat(result).isEqualTo(expected);
        verify(twelveDataClient).search("AAPL");
        verifyNoInteractions(stockPriceClient, upbitPriceClient);
    }

    @Test
    void coinRoutesToUpbitListMarketsAndFiltersInMemory() {
        when(searchThrottle.tryAcquire(userId)).thenReturn(true);
        when(searchCacheStore.find("search:COIN:ALL")).thenReturn(Optional.empty());
        List<SearchResult> allMarkets = List.of(
                new SearchResult("KRW-BTC", "Bitcoin", AssetType.COIN, "KRW"),
                new SearchResult("KRW-ETH", "Ethereum", AssetType.COIN, "KRW"),
                new SearchResult("KRW-XRP", "Ripple", AssetType.COIN, "KRW"));
        when(upbitPriceClient.listMarkets()).thenReturn(allMarkets);

        List<SearchResult> result = searchService.search(userId, AssetType.COIN, "KRW", "bit");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ticker()).isEqualTo("KRW-BTC");
        verify(upbitPriceClient).listMarkets();
        verifyNoInteractions(stockPriceClient, twelveDataClient);
    }

    @Test
    void coinFilterMatchesOnName() {
        when(searchThrottle.tryAcquire(userId)).thenReturn(true);
        when(searchCacheStore.find("search:COIN:ALL")).thenReturn(Optional.empty());
        List<SearchResult> allMarkets = List.of(
                new SearchResult("KRW-BTC", "Bitcoin", AssetType.COIN, "KRW"),
                new SearchResult("KRW-ETH", "Ethereum", AssetType.COIN, "KRW"));
        when(upbitPriceClient.listMarkets()).thenReturn(allMarkets);

        List<SearchResult> result = searchService.search(userId, AssetType.COIN, "KRW", "ethereum");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ticker()).isEqualTo("KRW-ETH");
    }

    @Test
    void noMatchReturnsEmptyList() {
        when(searchThrottle.tryAcquire(userId)).thenReturn(true);
        when(searchCacheStore.find(anyString())).thenReturn(Optional.empty());
        when(stockPriceClient.search("없는종목")).thenReturn(List.of());

        List<SearchResult> result = searchService.search(userId, AssetType.STOCK, "KRW", "없는종목");

        assertThat(result).isEmpty();
    }

    @Test
    void cashAssetTypeThrowsSearchValidationException() {
        assertThatThrownBy(() -> searchService.search(userId, AssetType.CASH, "KRW", "원화"))
                .isInstanceOf(SearchValidationException.class);
        verifyNoInteractions(stockPriceClient, twelveDataClient, upbitPriceClient);
    }

    @Test
    void throttleExceededThrowsSearchRateLimitExceededException() {
        when(searchCacheStore.find(anyString())).thenReturn(Optional.empty());
        when(searchThrottle.tryAcquire(userId)).thenReturn(false);

        assertThatThrownBy(() -> searchService.search(userId, AssetType.STOCK, "KRW", "삼성"))
                .isInstanceOf(SearchRateLimitExceededException.class);
        verify(searchCacheStore).find(anyString());
        verifyNoInteractions(stockPriceClient, twelveDataClient, upbitPriceClient);
    }

    @Test
    void cacheHitSkipsExternalClientCalls() {
        List<SearchResult> cached = List.of(new SearchResult("005930", "삼성전자", AssetType.STOCK, "KRW"));
        when(searchCacheStore.find("search:STOCK:KRW:%EC%82%BC%EC%84%B1")).thenReturn(Optional.of(cached));

        List<SearchResult> result = searchService.search(userId, AssetType.STOCK, "KRW", "삼성");

        assertThat(result).isEqualTo(cached);
        verify(searchThrottle, never()).tryAcquire(any());
        verify(searchCacheStore, never()).save(anyString(), any());
        verifyNoInteractions(stockPriceClient, twelveDataClient, upbitPriceClient);
    }

    @Test
    void coinCacheHitFiltersInMemoryWithoutCallingUpbit() {
        List<SearchResult> cachedAll = List.of(
                new SearchResult("KRW-BTC", "Bitcoin", AssetType.COIN, "KRW"),
                new SearchResult("KRW-ETH", "Ethereum", AssetType.COIN, "KRW"));
        when(searchCacheStore.find("search:COIN:ALL")).thenReturn(Optional.of(cachedAll));

        List<SearchResult> result = searchService.search(userId, AssetType.COIN, "KRW", "eth");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ticker()).isEqualTo("KRW-ETH");
        verify(searchThrottle, never()).tryAcquire(any());
        verifyNoInteractions(upbitPriceClient);
    }

    @Test
    void coinWithUnsupportedCurrencyThrowsSearchValidationException() {
        // cache miss 후 throttle 통과, route에서 예외
        when(searchCacheStore.find("search:COIN:ALL")).thenReturn(Optional.empty());
        when(searchThrottle.tryAcquire(userId)).thenReturn(true);

        assertThatThrownBy(() -> searchService.search(userId, AssetType.COIN, "JPY", "btc"))
                .isInstanceOf(SearchValidationException.class);
        verifyNoInteractions(upbitPriceClient);
    }

    /**
     * [Major] currency 필터 검증: listMarkets()가 KRW·USD 마켓을 혼합 반환할 때
     * currency=KRW 요청에는 KRW 마켓만, currency=USD 요청에는 USD 마켓만 나와야 한다.
     */
    @Test
    void coinFilterExcludesDifferentCurrencyMarkets() {
        when(searchThrottle.tryAcquire(userId)).thenReturn(true);
        when(searchCacheStore.find("search:COIN:ALL")).thenReturn(Optional.empty());
        List<SearchResult> allMarkets = List.of(
                new SearchResult("KRW-BTC", "Bitcoin", AssetType.COIN, "KRW"),
                new SearchResult("USD-BTC", "Bitcoin", AssetType.COIN, "USD"));
        when(upbitPriceClient.listMarkets()).thenReturn(allMarkets);

        List<SearchResult> result = searchService.search(userId, AssetType.COIN, "KRW", "btc");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ticker()).isEqualTo("KRW-BTC");
        assertThat(result.get(0).currency()).isEqualTo("KRW");
    }
}
