package com.allfolio;

import com.allfolio.infra.price.StockProperties;
import com.allfolio.infra.price.TwelveDataProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StockProperties.serviceKey / TwelveDataProperties.apiKey가 빈 문자열로 바인딩돼도
 * ApplicationContext 로딩이 성공한다는 정책(루트 CLAUDE.md: "ALLFOLIO_STOCK_SERVICE_KEY 미설정
 * 시에도 부팅은 정상")을 직접 검증한다.
 *
 * <p>{@link AbstractIntegrationTest}는 {@code @DynamicPropertySource}에서
 * {@code allfolio.stock.service-key}에 항상 더미값("test-service-key")을 주입하므로, 이를
 * 상속하는 테스트 클래스 안에서는 "빈 값으로 바인딩해도 부팅에 성공하는지"를 재현할 수 없다
 * ({@code PricePropertiesTest} 참고). 전체 애플리케이션을 띄우지 않고 {@link ApplicationContextRunner}로
 * 이 두 {@code @ConfigurationProperties} 클래스만 최소 구성으로 띄워 바인딩 성공 여부만 확인한다.
 */
class StockAndTwelveDataPropertiesEmptyKeyBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "allfolio.stock.base-url=https://apis.data.go.kr/test",
                    "allfolio.stock.service-key=",
                    "allfolio.twelvedata.base-url=https://api.twelvedata.com",
                    "allfolio.twelvedata.api-key="
            );

    @Test
    void contextLoadsSuccessfullyWithEmptyServiceKeyAndApiKey() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StockProperties.class).serviceKey()).isEmpty();
            assertThat(context.getBean(TwelveDataProperties.class).apiKey()).isEmpty();
        });
    }

    @EnableConfigurationProperties({StockProperties.class, TwelveDataProperties.class})
    static class TestConfig {
    }
}
