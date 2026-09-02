# DB 마이그레이션 규칙

Flyway는 이 폴더의 `V*.sql`을 자동 실행한다.

**핵심 원칙:**
- 모든 금융 컬럼은 `NUMERIC(28,8)` 사용 (BigDecimal 1:1 매핑)
- 낙관적 잠금(Optimistic Lock)을 위해 `version INT` 컬럼 필수 (holdings는 사용자 액션 기반 저빈도 쓰기 — BIGINT 불필요)
- UUID v7은 PK 기본값 (`DEFAULT uuidv7()`)

테스트는 **Testcontainers**(자동 관리형 PostgreSQL)를 사용하므로 마이그레이션이 매번 검증된다.
