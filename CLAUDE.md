# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## 빌드 및 실행 명령어

### 프로젝트 빌드
```bash
./gradlew build
```

### 테스트 실행
```bash
# 전체 테스트
./gradlew test

# 특정 테스트 실행
./gradlew test --tests com.allfolio.domain.asset.AssetTest

# 테스트 output 자세히 보기
./gradlew test --info
```

### 앱 실행
```bash
# Docker Compose로 PostgreSQL 시작
docker compose up -d

# 앱 시작
./gradlew bootRun

# 헬스 체크 (정상 부팅 확인)
curl http://localhost:8080/actuator/health

# Prometheus 메트릭 확인
curl http://localhost:8080/actuator/prometheus
```

### 개발 시 유용한 명령어
```bash
# 특정 클래스의 테스트만 재실행 (watch 없음)
./gradlew test --tests "*AssetControllerTest"

# 의존성 확인
./gradlew dependencies

# IDE 설정 재생성
./gradlew idea
```

---

## 아키텍처 개요

AllFolio는 **Phase 기반 점진적 개발**로 설계되었습니다. 현재는 **Phase 1 (자산 CRUD + 시뮬레이터)** 진행 중입니다.

### 핵심 기술 스택

| 계층 | 기술 |
|---|---|
| **Java/Framework** | Java 25, Spring Boot 4.1, Spring Data JPA |
| **DB** | PostgreSQL 18, Flyway (마이그레이션), NUMERIC(28,8) (금융 정밀도) |
| **동시성** | Virtual Threads (`spring.threads.virtual.enabled=true`) |
| **Observability** | Prometheus 메트릭, 구조화 로깅 (Logstash) |
| **보안** | Spring Security, JWT (Phase 1 Step 3) |

### 계층 구조

프로젝트는 표준 엔터프라이즈 구조를 따릅니다:
- **`web`** — REST 컨트롤러 (HTTP 엔트리포인트)
- **`domain`** — 비즈니스 로직 (자산, 포트폴리오, 시뮬레이션)
- **`infra`** — 영속성 (JPA 리포지토리, DB 접근)
- **`config`** — Spring 빈 설정, 보안 정책

### Phase 기반 로드맵

| Phase | 초점 | 기술 |
|---|---|---|
| **Phase 1** (현재) | 자산 CRUD + In-Memory 시뮬레이터 | BigDecimal, Flyway, JWT 기본 |
| **Phase 2** | 외부 시세 API 연동 + Redis 캐시 | Upbit/KIS API, Redis, Throttling |
| **Phase 3** | SSE 실시간 스트리밍 + 평단가 합성 | Virtual Threads, SSE, 시세 틱 스트리밍 |
| **Phase 4** | 대용량 부하 및 하이브리드 앱 | FCM/APNs, k6 벤치마크 |

자세한 내용은 [`docs/PHASE1_PLAN.md`](docs/PHASE1_PLAN.md)와 [`docs/PRD.md`](docs/PRD.md) 참조.

---

## 데이터베이스

### 마이그레이션 구조

Flyway는 `src/main/resources/db/migration/V*.sql`에서 마이그레이션을 자동 실행합니다.

**핵심 원칙:**
- 모든 금융 컬럼은 `NUMERIC(28,8)` 사용 (BigDecimal 1:1 매핑)
- 낙관적 잠금(Optimistic Lock)을 위해 `version INT` 컬럼 필수 (holdings는 사용자 액션 기반 저빈도 쓰기 — BIGINT 불필요)
- UUID v7은 PK 기본값 (`DEFAULT uuidv7()`)

### 로컬 개발 DB 설정

```bash
# Docker에서 PG 시작
docker compose up -d postgres

# DB 연결 (필요시)
psql -h localhost -U allfolio -d allfolio
```

테스트는 **Testcontainers**(자동 관리형 PG)를 사용하므로 마이그레이션이 매번 검증됩니다.

---

## 금융 정밀도 (중요)

### BigDecimal 사용

- **원칙:** 금융 계산에는 `double`/`float` 절대 금지. 모든 금액/비율/가중치는 `BigDecimal`.
- **DB:** `NUMERIC(28,8)` ↔ JPA `@Column(columnDefinition = "NUMERIC(28,8)")`로 1:1 매핑
- **테스트:** `BigDecimal` 비교는 `.compareTo()` 사용 (`.equals()`는 scale 차이로 실패 가능)

### 정밀도 검증

```bash
# double 사용 여부 확인 (CI/정적 검사 포함)
grep -r "double " src/main/java --include="*.java" | grep -v "Double\|//.*double"
```

---

## 물타기 시뮬레이터 (FR-02) 성능 KPI

- **응답시간 P99:** ≤ 5ms (1,000회 반복 호출)
- **메트릭:** `allfolio.simulation.duration` (Prometheus)로 추적

시뮬레이터는 **In-Memory 계산**이므로 DB 조회 없음. JVM 워밍업 후 검증.

---

## 주요 도메인 개념

### 자산 (Asset)

- 종목당 1개 `Holding` (stock/crypto/cash)
- 평단가(`avg_price`), 수량(`quantity`)을 `BigDecimal`로 추적
- 시뮬레이터 입력: 추가 매수 가격 × 수량 → 신규 평단가 계산

### 포트폴리오 (Portfolio)

- 사용자의 모든 자산 집계 + KRW 기준 비중 계산
- Phase 2부터 외부 시세 API와 연동 → 평가 손익 추가

### 시뮬레이션

- 요청에 따라 `POST /v1/simulate/avg-price` 호출
- DB 저장 없이 메모리에서 계산 후 반환
- 결과는 "만약 X원에 Y주를 추가 매수하면 평단가는?" 형태

---

## 주의사항

### Virtual Threads 활성화

`application.yml`에서 `spring.threads.virtual.enabled: true`로 설정되어 있습니다.
- SSE 스트리밍(Phase 3)에서 1,000+ 동시 커넥션 지원
- Thread 풀 기반 설정(`ThreadPoolTaskExecutor` 등) 제거 시 주의

### JPA Open-in-View 비활성화

```yaml
spring.jpa.open-in-view: false
```

뷰 렌더링 중 lazy 로딩 방지 (REST API이므로 필요 없음).

---

## 커밋 컨벤션

Phase 커밋은 다음 형식을 따릅니다:

```
Phase 1 Step N — [기능 요약]

[상세 설명 (필요시)]

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>
```

예:
```
Phase 1 Step 2 — DB 스키마 & Flyway 마이그레이션

- users, assets, holdings, transactions 테이블 정의
- NUMERIC(28,8) 컬럼, UUID v7 PK, 낙관적 잠금
- Testcontainers로 마이그레이션 검증
```

---

## 참고 문서

- **[PRD](docs/PRD.md)** — 전체 프로젝트 명세, 문제 정의, 데이터 모델
- **[Phase 1 계획](docs/PHASE1_PLAN.md)** — 현재 Phase의 Step별 작업, 완료 기준, 주요 산출물
- **[Spring Boot 레퍼런스](https://spring.io/projects/spring-boot)** — 프레임워크 문서
