---
paths:
  - "src/test/java/**/*.java"
---

# 백엔드 테스트 규칙

## 간헐적 실패 트러블슈팅

`./gradlew test` 전체 스위트 실행 시 간헐적 실패가 나는데 실패한 클래스만 단독 실행(`--tests`)하면 항상 통과한다면, 로직 버그가 아니라 `AbstractIntegrationTest`의 Testcontainers `static @Container` 공유 구조로 인한 기존 인프라 이슈일 가능성이 높다. 먼저 단독 실행으로 격리해 재현되는지부터 확인할 것.

## BigDecimal 비교

`BigDecimal` 비교는 `.compareTo()` 사용 (`.equals()`는 scale 차이로 실패 가능).
단, `compareTo`/`isEqualByComparingTo`만 쓰면 스케일(자리수) 버그는 못 잡는다 — 금융 필드를 검증할 땐 최소 1건은 정확한 문자열(`isEqualTo("1000000")`)로 스케일까지 단언할 것(Task 023 실측).

## Resilience4j CircuitBreaker 테스트 격리

같은 Spring 컨텍스트를 공유하는 통합 테스트에서 CircuitBreaker(`slidingWindowSize` 등)의 실패 카운트가 테스트 간에 누적된다(Task 023 실측) — 관련 `CircuitBreakerRegistry` 인스턴스를 `@BeforeEach`에서 `reset()`할 것.

## @ConfigurationProperties 경계 케이스는 ApplicationContextRunner로

`AbstractIntegrationTest`는 공유 `@DynamicPropertySource`로 시크릿류에 항상 더미값을 주입해, "빈 값 바인딩" 같은 경계 케이스를 그 안에서는 재현할 수 없다(Task 025 실측). 전체 `@SpringBootTest` 대신 `ApplicationContextRunner` + `@EnableConfigurationProperties`만 등록한 최소 `TestConfig`로 해당 프로퍼티 클래스만 가볍게 띄워 검증할 것.
