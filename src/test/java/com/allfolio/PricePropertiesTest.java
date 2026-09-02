package com.allfolio;

import com.allfolio.infra.price.ExchangeRateProperties;
import com.allfolio.infra.price.UpbitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml의 allfolio.upbit / allfolio.exchange-rate 설정이
 * 각 ConfigurationProperties 빈으로 정상 바인딩되는지 확인하는 컨텍스트 로드 테스트.
 */
class PricePropertiesTest extends AbstractIntegrationTest {

    @Autowired
    private UpbitProperties upbitProperties;

    @Autowired
    private ExchangeRateProperties exchangeRateProperties;

    @Test
    void upbitBaseUrlIsBound() {
        assertThat(upbitProperties.baseUrl()).isEqualTo("https://api.upbit.com");
    }

    @Test
    void exchangeRateBaseUrlIsBound() {
        assertThat(exchangeRateProperties.baseUrl())
                .isEqualTo("https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest");
    }
}
