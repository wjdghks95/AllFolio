package com.allfolio.config;

import com.allfolio.infra.cache.PriceCacheProperties;
import com.allfolio.infra.cache.PriceThrottleProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PriceCacheProperties.class, PriceThrottleProperties.class})
public class CacheConfig {
}
