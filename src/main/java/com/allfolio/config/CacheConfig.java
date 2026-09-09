package com.allfolio.config;

import com.allfolio.infra.cache.PriceCacheProperties;
import com.allfolio.infra.cache.PriceThrottleProperties;
import com.allfolio.infra.cache.SearchCacheProperties;
import com.allfolio.infra.cache.SearchThrottleProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PriceCacheProperties.class, PriceThrottleProperties.class,
        SearchCacheProperties.class, SearchThrottleProperties.class})
public class CacheConfig {
}
