package com.allfolio.infra.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;

/**
 * CandleCacheEntry 전용 RedisTemplate. {@link PriceCacheRedisConfig}와 동일한 이유로 Jackson 3
 * ({@code tools.jackson.*}) 기반 {@link JacksonJsonRedisSerializer}를 캐시 엔트리 타입에 직접
 * 바인딩한다 — Generic 계열 직렬화기는 BigDecimal 정밀도를 잃을 수 있어 피한다(Task 022 조사).
 */
@Configuration
public class CandleCacheRedisConfig {

    @Bean
    public RedisTemplate<String, CandleCacheEntry> candleCacheRedisTemplate(
            RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, CandleCacheEntry> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new JacksonJsonRedisSerializer<>(objectMapper, CandleCacheEntry.class));
        template.afterPropertiesSet();
        return template;
    }
}
