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
./gradlew test --tests com.allfolio.AuthIntegrationTest
./gradlew test --tests com.allfolio.SchemaMigrationTest

# 테스트 output 자세히 보기
./gradlew test --info
```

### 앱 실행
```bash
# Docker Compose로 PostgreSQL 시작
docker compose up -d

# 앱 시작 (JWT 시크릿 미설정 시 부팅 실패 — 의도된 동작, 아래 참고)
ALLFOLIO_JWT_SECRET=$(openssl rand -base64 32) ./gradlew bootRun

# 헬스 체크 (정상 부팅 확인)
curl http://localhost:8080/actuator/health

# Prometheus 메트릭 확인
curl http://localhost:8080/actuator/prometheus
```

`ALLFOLIO_JWT_SECRET`은 기본값이 빈 문자열이라, 설정하지 않으면 앱이 **즉시 부팅 실패**합니다. 약한 키가 조용히 쓰이는 것을 막기 위한 의도된 동작이므로 기본값을 채워 넣지 마세요.

### 개발 시 유용한 명령어
```bash
# 특정 클래스의 테스트만 재실행 (watch 없음)
./gradlew test --tests "*AuthIntegrationTest"

# 의존성 확인
./gradlew dependencies

# IDE 설정 재생성
./gradlew idea
```

### 프론트엔드 실행 (Task 004부터)

```bash
cd frontend
npm install
npm run dev   # Vite 개발 서버, 기본 포트 5173
```

Vite 개발 서버는 `/v1/*` 요청을 `localhost:8080`(Spring Boot)으로 프록시합니다. 브라우저에서 같은 출처로 보이므로 개발 중에는 CORS 설정이 필요 없습니다. 백엔드(`./gradlew bootRun`)와 프론트(`npm run dev`)를 동시에 띄운 상태에서 `http://localhost:5173`으로 접속해 개발합니다.

---

## 아키텍처 개요

AllFolio는 **Phase 기반 점진적 개발**로 설계되었습니다. Phase 1(애플리케이션 골격 구축, Task 001~006)이 완료됐고, 현재는 **Phase 2 (UI/UX 완성 + 백엔드 도메인 구현, 병렬 2트랙)** 진행 중입니다 — 프론트는 Task 007(공통 컴포넌트)부터, 백엔드는 Task 012(자산 CRUD API)부터 시작합니다. 진행 상황은 [`docs/ROADMAP.md`](docs/ROADMAP.md) 「개발 단계」 절 참조.

### 핵심 기술 스택

| 계층 | 기술 |
|---|---|
| **Java/Framework** | Java 25, Spring Boot 4.1, Spring Data JPA |
| **DB** | PostgreSQL 18, Flyway (마이그레이션), NUMERIC(28,8) (금융 정밀도) |
| **동시성** | Virtual Threads (`spring.threads.virtual.enabled=true`) |
| **Observability** | Prometheus 메트릭, 구조화 로깅 (Logstash) |
| **보안** | Spring Security, JWT (Task 003) |
| **프론트엔드** | React + Vite + TypeScript (SPA), Capacitor (하이브리드 앱) — `frontend/` |

### 계층 구조

백엔드는 표준 엔터프라이즈 구조를, 프론트엔드는 별도 디렉터리를 따릅니다:

```
src/main/java/com/allfolio/
  web/         REST 컨트롤러 + dto/ (요청·응답 객체)
  domain/      엔티티 + service/ (비즈니스 로직) + repository/ + exception/
  infra/       security/ (JWT 발급·검증 필터)
  config/      Spring 빈 설정, 보안 정책

frontend/      React + Vite + TypeScript (Task 004부터)
```

### Phase 기반 로드맵

| Phase | 초점 | 기술 |
|---|---|---|
| **Phase 1** | 애플리케이션 골격 구축 (백엔드 인증·프론트 라우팅·API 계약) | Spring Security, JWT, React, Vite |
| **Phase 2** (현재) | UI/UX 완성(더미 데이터) + 백엔드 자산 CRUD·시뮬레이터 (병렬 트랙) | React 컴포넌트, BigDecimal, In-Memory 시뮬레이터 |
| **Phase 3** | 실데이터 연동 + 외부 시세 API + Redis 캐시 | Upbit/KIS API, Redis, Throttling |
| **Phase 4** | SSE 실시간 스트리밍·하이브리드 앱·대용량 부하 | Virtual Threads, SSE, FCM/APNs, k6, Capacitor |

이 표의 Phase 번호는 `docs/ROADMAP.md`의 신규 체계를 따릅니다(기존 "Phase 2 = 외부 시세"였던 이전 체계와 다름 — 대응표는 ROADMAP.md 참조). 자세한 내용은 [`docs/ROADMAP.md`](docs/ROADMAP.md)와 [`docs/PRD.md`](docs/PRD.md) 참조.

---

## Spring Boot 4 특이사항

Phase와 무관하게 이 저장소에서 계속 유효한 환경 제약입니다. 실측으로 확인된 함정이므로 재발 시 먼저 이 표부터 확인하세요.

| 항목 | 내용 |
|---|---|
| Flyway 의존성 | `flyway-core`만으로는 오토컨피규레이션(설정 없이 기능을 자동 활성화하는 스프링 장치)이 로드되지 않아 마이그레이션이 조용히 건너뛰어짐. `org.springframework.boot:spring-boot-flyway` 모듈이 별도로 필요 |
| MockMvc | `@SpringBootTest`가 더 이상 MockMvc를 자동 제공하지 않음. `spring-boot-starter-webmvc-test`를 테스트 의존성에 별도 추가 |
| Security 자동 설정 | JWT 기반 무상태 인증이므로 `UserDetailsServiceAutoConfiguration`을 제외해야 함 — 제외하지 않으면 부팅 시 랜덤 생성 비밀번호가 로그에 남음 |
| Testcontainers 버전 | 버전을 고정하지 말 것. Spring Boot 4.1 BOM이 관리하는 2.x를 그대로 사용 (1.x로 고정하면 Docker Engine 29+와 API 버전 협상이 깨짐) |

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

## 물타기 시뮬레이터 (F006) 성능 KPI

- **응답시간 P99:** ≤ 5ms (1,000회 반복 호출)
- **메트릭:** `allfolio.simulation.duration` (Prometheus)로 추적

시뮬레이터는 **DB 쓰기 없음**. 대상 holding을 단건 조회한 뒤 In-Memory에서 가중평균을 계산한다 (조회는 발생하지만 저장은 하지 않는다). P99 목표치는 이 단건 조회를 포함한 수치다. JVM 워밍업 후 검증.

---

## 주요 도메인 개념

### 자산 (Asset)

- 종목당 1개 `Holding` (stock/crypto/cash)
- 평단가(`avg_price`), 수량(`quantity`)을 `BigDecimal`로 추적
- 시뮬레이터 입력: 추가 매수 가격 × 수량 → 신규 평단가 계산
- **CASH 자산의 `avg_price`는 항상 `1`** (Task 006 결정). 현금에는 평단가 개념이 없지만 `holdings.avg_price`에 `CHECK (avg_price > 0)` 제약이 있어 `0`을 넣을 수 없다. `1`을 고정값으로 써서 "평가금액 = quantity × avg_price" 계산식을 CASH에도 그대로 적용할 수 있게 한다(문자 그대로 "1원짜리 단위 × 보유량"). 등록 화면에서는 CASH 선택 시 평단가 입력란을 숨긴다

### 포트폴리오 (Portfolio)

- 사용자의 모든 자산 집계 + KRW 기준 비중 계산
- Phase 3(포트폴리오 평가금액·비중·손익, ROADMAP Task 023)부터 평가 손익 추가

### 시뮬레이션

- 요청에 따라 `POST /v1/simulate/avg-price` 호출
- DB 저장 없이 메모리에서 계산 후 반환
- 결과는 "만약 X원에 Y주를 추가 매수하면 평단가는?" 형태

---

## 주의사항

### Virtual Threads 활성화

`application.yml`에서 `spring.threads.virtual.enabled: true`로 설정되어 있습니다.
- SSE 스트리밍(Phase 4, ROADMAP Task 025·028)에서 1,000+ 동시 커넥션 지원
- Thread 풀 기반 설정(`ThreadPoolTaskExecutor` 등) 제거 시 주의

### JPA Open-in-View 비활성화

```yaml
spring.jpa.open-in-view: false
```

뷰 렌더링 중 lazy 로딩 방지 (REST API이므로 필요 없음).

---

## 참고 문서

- **[PRD](docs/PRD.md)** — 화면·기능 중심 MVP 명세(F001~F010), 화면 경로, 클라이언트 스택, 데이터 모델. API 규격·에러 포맷·성능 KPI·리스크는 다루지 않음
- **[개발 로드맵](docs/ROADMAP.md)** — Phase/Task별 진행 상황, API 규격·에러 포맷·성능 KPI·리스크의 원본(single source of truth), 착수 전 결정 사항, Phase 번호 대응표
- **[디자인 시스템](docs/DESIGN.md)** — 팔레트·타이포·간격 등 디자인 토큰, 화면 골격 규약, 컴포넌트 시각 규약의 단일 출처
- **[Spring Boot 레퍼런스](https://spring.io/projects/spring-boot)** — 프레임워크 문서
