package com.allfolio;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers 기반 통합 테스트 공통 베이스.
 * Step 4/6 등 이후 통합 테스트에서도 재사용한다.
 *
 * <p>Singleton Container 패턴을 쓴다 — {@code @Testcontainers}/{@code @Container}는 서브클래스마다
 * 컨테이너를 재기동하는데, 여러 서브클래스가 같은 JVM에서 순차 실행되면 한 클래스가 끝나며 컨테이너를
 * 내린 직후 Spring이 캐시해둔 이전 컨텍스트를 다음 클래스가 재사용하면서 죽은 포트로 접속을 시도해
 * {@code SQLTransientConnectionException}이 간헐적으로 발생했다(통합 테스트 클래스가 4개로 늘며 실측
 * 재현). 정적 초기화 블록에서 1회만 start()하고 절대 stop()하지 않는 방식으로 이를 막는다 — JVM 종료
 * 시 Testcontainers의 Ryuk 리소스 정리 데몬이 컨테이너를 정리한다.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    /** 테스트 전용 HS256 시크릿 (32바이트 이상). 운영은 ALLFOLIO_JWT_SECRET 환경변수를 쓴다. */
    protected static final String TEST_JWT_SECRET = "allfolio-test-secret-key-for-hs256-at-least-32-bytes";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.jwt.secret", () -> TEST_JWT_SECRET);
        // StockProperties.serviceKey는 @NotBlank라 ALLFOLIO_STOCK_SERVICE_KEY 미설정 시
        // 컨텍스트 로드 자체가 실패한다 — 실키가 없는 테스트 환경에서도 부팅되도록 더미 값을 주입한다.
        registry.add("allfolio.stock.service-key", () -> "test-service-key");
    }
}
