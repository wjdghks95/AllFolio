package com.allfolio;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers 기반 통합 테스트 공통 베이스.
 * Step 4/6 등 이후 통합 테스트에서도 재사용한다.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    /** 테스트 전용 HS256 시크릿 (32바이트 이상). 운영은 ALLFOLIO_JWT_SECRET 환경변수를 쓴다. */
    protected static final String TEST_JWT_SECRET = "allfolio-test-secret-key-for-hs256-at-least-32-bytes";

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.jwt.secret", () -> TEST_JWT_SECRET);
    }
}
