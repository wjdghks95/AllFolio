package com.allfolio.infra.cache;

import com.allfolio.domain.SearchCacheEntry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;

/**
 * SearchCacheEntry 전용 RedisTemplate. PriceCacheRedisConfig와 동일 패턴 — 범용(다형성) 직렬화기는
 * BigDecimal 정밀도를 잃을 수 있어(Task 022 조사) 캐시 엔트리 타입에 직접 바인딩된 JacksonJsonRedisSerializer를 쓴다.
 */
@Configuration
public class SearchCacheRedisConfig {

    @Bean
    public RedisTemplate<String, SearchCacheEntry> searchCacheRedisTemplate(
            RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, SearchCacheEntry> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new JacksonJsonRedisSerializer<>(objectMapper, SearchCacheEntry.class));
        template.afterPropertiesSet();
        return template;
    }
}
