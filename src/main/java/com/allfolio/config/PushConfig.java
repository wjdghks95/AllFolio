package com.allfolio.config;

import com.allfolio.infra.push.FcmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FcmProperties.class)
public class PushConfig {
}
