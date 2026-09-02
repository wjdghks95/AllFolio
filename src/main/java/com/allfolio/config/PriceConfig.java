package com.allfolio.config;

import com.allfolio.infra.price.ExchangeRateProperties;
import com.allfolio.infra.price.StockProperties;
import com.allfolio.infra.price.UpbitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({UpbitProperties.class, ExchangeRateProperties.class, StockProperties.class})
public class PriceConfig {
}
