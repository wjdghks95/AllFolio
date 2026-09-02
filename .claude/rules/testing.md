---
paths:
  - "src/test/java/**/*.java"
---

# 백엔드 테스트 규칙

## 간헐적 실패 트러블슈팅

`./gradlew test` 전체 스위트 실행 시 간헐적 실패가 나는데 실패한 클래스만 단독 실행(`--tests`)하면 항상 통과한다면, 로직 버그가 아니라 `AbstractIntegrationTest`의 Testcontainers `static @Container` 공유 구조로 인한 기존 인프라 이슈일 가능성이 높다. 먼저 단독 실행으로 격리해 재현되는지부터 확인할 것.

## BigDecimal 비교

`BigDecimal` 비교는 `.compareTo()` 사용 (`.equals()`는 scale 차이로 실패 가능).
