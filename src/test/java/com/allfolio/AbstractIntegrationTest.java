package com.allfolio;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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

    // 외부 시세 클라이언트 테스트(Upbit/Stock/ExchangeRate/TwelveData 등)는 각자 다른
    // @DynamicPropertySource(base-url)를 등록해 서로 다른 ApplicationContext로 캐싱된다 — 클라이언트
    // 테스트 클래스가 늘어날수록 HikariCP 커넥션 풀(컨텍스트당 최대 10개)이 그만큼 늘어 기본
    // max_connections=100인 Postgres를 전체 스위트 실행 중 소진시킨다("FATAL: sorry, too many
    // clients already", TwelveDataClientTest 추가 시 실측 재현). 커넥션 풀 개수 자체를 줄이는 대신
    // Postgres 쪽 한도를 넉넉히 올려 대응한다.
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withCommand("postgres", "-c", "max_connections=300");

    /** GenericContainer는 이미지 이름만으로 종류를 추론하지 못해 name="redis"를 명시해야 한다(Task 022). */
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.8"))
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("allfolio.jwt.secret", () -> TEST_JWT_SECRET);
        // StockProperties.serviceKey는 @NotBlank가 아니라 빈 값이어도 컨텍스트 로드는 성공한다.
        // 그래도 더미 값을 주입하는 이유는 StockPriceClientTest가 이 값이 요청 URL에 그대로
        // 들어가길 기대하기 때문이다(WireMock 스텁이 serviceKey 쿼리 파라미터 값을 매칭에 사용).
        registry.add("allfolio.stock.service-key", () -> "test-service-key");
    }
}
