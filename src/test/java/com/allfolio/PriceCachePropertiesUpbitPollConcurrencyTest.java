package com.allfolio;

import com.allfolio.infra.cache.PriceCacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * code-reviewer Major 3 회귀: allfolio.price-cache.upbit-poll-concurrency가 0/음수면 부팅이 즉시
 * 실패해야 한다(예전에는 서버가 정상 기동(헬스체크 UP)한 채로 CandlePushScheduler의 permit 획득이
 * 전부 막혀 SSE 캔들 이벤트가 조용히 0건이 되는 무음 장애로 이어졌다). 이 키가 아예 없을 때는
 * {@code @DefaultValue("25")}로 폴백해 바인딩 자체가 int 기본값 0이 되는 것을 막는다.
 *
 * <p>{@code PricePropertiesTest}는 {@link AbstractIntegrationTest}의 공유 {@code @DynamicPropertySource}
 * 환경이라 "설정값이 아예 없거나 0인" 경계 케이스를 재현할 수 없다(.claude/rules/testing.md) —
 * {@link StockAndTwelveDataPropertiesEmptyKeyBindingTest}와 동일하게 {@link ApplicationContextRunner}로
 * {@link PriceCacheProperties}만 최소 구성으로 띄운다.
 */
class PriceCachePropertiesUpbitPollConcurrencyTest {

    private static final String[] REQUIRED_DURATIONS = {
            "allfolio.price-cache.coin-fresh-ttl=10s",
            "allfolio.price-cache.stock-fresh-ttl=12h",
            "allfolio.price-cache.stock-us-fresh-ttl=1m",
            "allfolio.price-cache.cash-usd-fresh-ttl=12h",
            "allfolio.price-cache.stale-ceiling=24h",
            "allfolio.price-cache.negative-ttl=30s"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(REQUIRED_DURATIONS);

    @Test
    void contextFailsToStartWhenUpbitPollConcurrencyIsZero() {
        contextRunner.withPropertyValues("allfolio.price-cache.upbit-poll-concurrency=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextFailsToStartWhenUpbitPollConcurrencyIsNegative() {
        contextRunner.withPropertyValues("allfolio.price-cache.upbit-poll-concurrency=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    /** yml 키 자체가 통째로 빠져도(바인딩 대상 없음) int 기본값 0이 아니라 25로 폴백해야 한다. */
    @Test
    void upbitPollConcurrencyDefaultsTo25WhenPropertyIsAbsent() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PriceCacheProperties.class).upbitPollConcurrency()).isEqualTo(25);
        });
    }

    @EnableConfigurationProperties(PriceCacheProperties.class)
    static class TestConfig {
    }
}
