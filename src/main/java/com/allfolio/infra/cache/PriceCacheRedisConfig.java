package com.allfolio.infra.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;

/**
 * PriceCacheEntry 전용 RedisTemplate. Spring Boot 4.1은 기본 JSON 라이브러리를 Jackson 3(tools.jackson.*)으로
 * 전환해 com.fasterxml.jackson.databind.ObjectMapper(Jackson 2) 빈을 더 이상 자동 등록하지 않는다 —
 * 그래서 이 프로젝트의 Jackson2JsonRedisSerializer는 deprecated이며 ObjectMapper 빈도 찾지 못한다(실측 확인).
 * Jackson 3 기반 JacksonJsonRedisSerializer를 캐시 엔트리 타입에 직접 바인딩해 쓴다 — Generic 계열
 * 직렬화기가 다형성 처리 중 BigDecimal이 중간 표현을 거치며 정밀도를 잃는 사례가 보고된 것(Task 022 조사)과
 * 같은 이유로, 타입 미지정 직렬화기를 피한다.
 */
@Configuration
public class PriceCacheRedisConfig {

    @Bean
    public RedisTemplate<String, PriceCacheEntry> priceCacheRedisTemplate(
            RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, PriceCacheEntry> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new JacksonJsonRedisSerializer<>(objectMapper, PriceCacheEntry.class));
        template.afterPropertiesSet();
        return template;
    }
}
