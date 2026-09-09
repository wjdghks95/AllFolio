package com.allfolio.infra.cache;

import com.allfolio.AbstractIntegrationTest;
import com.allfolio.domain.AssetType;
import com.allfolio.domain.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers Redis(AbstractIntegrationTest 공유 컨테이너)로 실제 Redis에 저장·조회해 통합 종목
 * 검색 결과 캐시를 검증한다(Task 026 서브태스크 3). PriceCacheStore와 달리 fresh/stale 구분이 없다.
 */
class SearchCacheStoreTest extends AbstractIntegrationTest {

    @Autowired
    private SearchCacheStore searchCacheStore;

    @Test
    void findReturnsSavedResultsRightAfterSave() {
        String key = "search:test:" + UUID.randomUUID();
        List<SearchResult> results = List.of(new SearchResult("BTC", "비트코인", AssetType.COIN, "KRW"));

        searchCacheStore.save(key, results);
        Optional<List<SearchResult>> lookup = searchCacheStore.find(key);

        assertThat(lookup).contains(results);
    }

    @Test
    void findReturnsEmptyForUnknownKey() {
        Optional<List<SearchResult>> lookup = searchCacheStore.find("search:test:" + UUID.randomUUID());

        assertThat(lookup).isEmpty();
    }
}
