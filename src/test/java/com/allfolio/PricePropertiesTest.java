package com.allfolio;

import com.allfolio.infra.cache.PriceCacheProperties;
import com.allfolio.infra.cache.PriceThrottleProperties;
import com.allfolio.infra.price.ExchangeRateProperties;
import com.allfolio.infra.price.StockProperties;
import com.allfolio.infra.price.TwelveDataProperties;
import com.allfolio.infra.price.UpbitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml의 allfolio.upbit / allfolio.exchange-rate / allfolio.stock /
 * allfolio.price-cache / allfolio.price-throttle 설정이 각 ConfigurationProperties 빈으로
 * 정상 바인딩되는지 확인하는 컨텍스트 로드 테스트.
 */
class PricePropertiesTest extends AbstractIntegrationTest {

    @Autowired
    private UpbitProperties upbitProperties;

    @Autowired
    private ExchangeRateProperties exchangeRateProperties;

    @Autowired
    private StockProperties stockProperties;

    @Autowired
    private TwelveDataProperties twelveDataProperties;

    @Autowired
    private PriceCacheProperties priceCacheProperties;

    @Autowired
    private PriceThrottleProperties priceThrottleProperties;

    @Test
    void upbitBaseUrlIsBound() {
        assertThat(upbitProperties.baseUrl()).isEqualTo("https://api.upbit.com");
    }

    @Test
    void exchangeRateBaseUrlIsBound() {
        assertThat(exchangeRateProperties.baseUrl())
                .isEqualTo("https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest");
    }

    /** serviceKey는 AbstractIntegrationTest가 @DynamicPropertySource로 주입하는 테스트용 더미값. */
    @Test
    void stockPropertiesAreBound() {
        assertThat(stockProperties.baseUrl())
                .isEqualTo("https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService");
        assertThat(stockProperties.serviceKey()).isEqualTo("test-service-key");
    }

    /**
     * apiKey는 @NotBlank가 없어(TwelveDataProperties 주석 참고) 미설정이어도 바인딩에 실패하지
     * 않는다는 것만 확인한다 — 실제 값 검증은 CI 환경변수 유무에 좌우돼 단언하지 않는다.
     */
    @Test
    void twelveDataBaseUrlIsBound() {
        assertThat(twelveDataProperties.baseUrl()).isEqualTo("https://api.twelvedata.com");
        assertThat(twelveDataProperties.apiKey()).isNotNull();
    }

    @Test
    void priceCacheFreshTtlAndStaleCeilingAreBound() {
        assertThat(priceCacheProperties.coinFreshTtl()).isEqualTo(Duration.ofSeconds(10));
        assertThat(priceCacheProperties.stockFreshTtl()).isEqualTo(Duration.ofHours(12));
        assertThat(priceCacheProperties.cashUsdFreshTtl()).isEqualTo(Duration.ofHours(12));
        assertThat(priceCacheProperties.staleCeiling()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void priceThrottleLimitAndWindowAreBound() {
        assertThat(priceThrottleProperties.limit()).isEqualTo(1);
        assertThat(priceThrottleProperties.window()).isEqualTo(Duration.ofSeconds(1));
    }
}
