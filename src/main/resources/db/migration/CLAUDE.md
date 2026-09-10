# DB 마이그레이션 규칙

Flyway는 이 폴더의 `V*.sql`을 자동 실행한다.

**파일 명명:** `V{n}__{설명}.sql` — 번호는 기존 파일 다음 정수, 설명은 언더스코어로 구분(`V3__add_search_cache_index.sql`). 더블 언더스코어(`__`) 필수.

**이미 적용된 파일은 절대 수정 금지** — Flyway가 파일 전체 내용으로 체크섬을 계산하므로 주석 한 글자만 바꿔도 기존 DB에서 부팅 시 `MigrationChecksum mismatch`로 실패한다. 수정이 필요하면 새 `V{n+1}__` 파일로 작성.

**핵심 원칙:**
- 모든 금융 컬럼은 `NUMERIC(28,8)` 사용 (BigDecimal 1:1 매핑)
- 낙관적 잠금(Optimistic Lock)을 위해 `version INT` 컬럼 필수 (holdings는 사용자 액션 기반 저빈도 쓰기 — BIGINT 불필요)
- UUID v7은 PK 기본값 (`DEFAULT uuidv7()`) — `uuidv7()` 함수는 `V1__init.sql` 상단에 정의된 프로젝트 자체 PL/pgSQL 함수(PostgreSQL 내장 아님)

테스트는 **Testcontainers**(자동 관리형 PostgreSQL)를 사용하므로 마이그레이션이 매번 검증된다.
