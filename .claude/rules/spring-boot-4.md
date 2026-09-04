---
paths:
  - "src/main/java/**/*.java"
  - "src/test/java/**/*.java"
---

# Spring Boot 4 특이사항

Phase와 무관하게 이 저장소에서 계속 유효한 환경 제약입니다. 실측으로 확인된 함정이므로 재발 시 먼저 이 표부터 확인하세요.

| 항목 | 내용 |
|---|---|
| Flyway 의존성 | `flyway-core`만으로는 오토컨피규레이션(설정 없이 기능을 자동 활성화하는 스프링 장치)이 로드되지 않아 마이그레이션이 조용히 건너뛰어짐. `org.springframework.boot:spring-boot-flyway` 모듈이 별도로 필요 |
| MockMvc | `@SpringBootTest`가 더 이상 MockMvc를 자동 제공하지 않음. `spring-boot-starter-webmvc-test`를 테스트 의존성에 별도 추가 |
| Security 자동 설정 | JWT 기반 무상태 인증이므로 `UserDetailsServiceAutoConfiguration`을 제외해야 함 — 제외하지 않으면 부팅 시 랜덤 생성 비밀번호가 로그에 남음 |
| Testcontainers 버전 | 버전을 고정하지 말 것. Spring Boot 4.1 BOM이 관리하는 2.x를 그대로 사용 (1.x로 고정하면 Docker Engine 29+와 API 버전 협상이 깨짐) |
| `RestClient.Builder` 자동 주입 | `spring-boot-starter-web`만으로는 `RestClientAutoConfiguration`이 로드되지 않아 `RestClient.Builder` 빈이 없다는 `NoSuchBeanDefinitionException`이 남 (Task 021 실측). `org.springframework.boot:spring-boot-restclient` 모듈을 별도로 추가해야 함 |
| Jackson 2 → 3 전환 | Spring Boot 4.1은 기본 JSON 라이브러리를 Jackson 3(`tools.jackson.*`)으로 전환해 `com.fasterxml.jackson.databind.ObjectMapper`(Jackson 2) 빈을 더 이상 자동 등록하지 않음(Task 022 실측). Spring Data Redis의 `Jackson2JsonRedisSerializer`(deprecated)를 주입하려 하면 `NoSuchBeanDefinitionException`이 남 — `JacksonJsonRedisSerializer`(`tools.jackson.databind.ObjectMapper` 사용)처럼 각 라이브러리의 "Jackson 2" 접미사 없는 버전을 찾아 써야 함 |

## Virtual Threads 활성화

`application.yml`에서 `spring.threads.virtual.enabled: true`로 설정되어 있습니다.
- SSE 스트리밍(Phase 4, ROADMAP Task 025·028)에서 1,000+ 동시 커넥션 지원
- Thread 풀 기반 설정(`ThreadPoolTaskExecutor` 등) 제거 시 주의

## JPA Open-in-View 비활성화

```yaml
spring.jpa.open-in-view: false
```

뷰 렌더링 중 lazy 로딩 방지 (REST API이므로 필요 없음).
