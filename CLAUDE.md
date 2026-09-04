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

# .env에 STOCK 서비스키를 둔 경우 셸에 로드 후 기동
set -a && source .env && set +a && ./gradlew bootRun

# 헬스 체크 (정상 부팅 확인)
curl http://localhost:8080/actuator/health

# Prometheus 메트릭 확인
curl http://localhost:8080/actuator/prometheus
```

`ALLFOLIO_JWT_SECRET`은 기본값이 빈 문자열이라, 설정하지 않으면 앱이 **즉시 부팅 실패**합니다. 약한 키가 조용히 쓰이는 것을 막기 위한 의도된 동작이므로 기본값을 채워 넣지 마세요.

`ALLFOLIO_STOCK_SERVICE_KEY`(공공데이터포털 주식시세 API 키)는 미설정이어도 **부팅은 정상적으로 됩니다** — STOCK 자산의 시세 조회(`GET /v1/assets/{id}/price`)만 실패합니다. `.gitignore`에 이미 포함된 `.env`에 저장해두고 위 커맨드로 로드하세요.

### 개발 시 유용한 명령어
```bash
# 특정 클래스의 테스트만 재실행 (watch 없음)
./gradlew test --tests "*AuthIntegrationTest"

# 의존성 확인
./gradlew dependencies

# IDE 설정 재생성
./gradlew idea
```

### 프론트엔드 실행

```bash
cd frontend
npm install
npm run dev   # Vite 개발 서버, 기본 포트 5173
```

백엔드(`./gradlew bootRun`)와 프론트(`npm run dev`)를 동시에 띄운 상태에서 `http://localhost:5173`으로 접속해 개발합니다. Vite 프록시 설정 등 `frontend/` 폴더 고유 규칙은 `frontend/CLAUDE.md` 참고.

---

## 아키텍처 개요

AllFolio는 **Phase 기반 점진적 개발**로 설계되었습니다. Phase 1(애플리케이션 골격 구축)과 Phase 2(UI/UX 완성 + 백엔드 도메인 구현)가 완료됐고(2026-08-27), 현재는 **Phase 3 (실데이터 연동 + 외부 시세 API + Redis 캐시)** 진행 중입니다 — Task 022(Redis 캐시·요청 Throttling)까지 완료됐고, 다음은 Task 023(포트폴리오 평가금액·비중·손익)입니다. 진행 상황은 [`docs/ROADMAP.md`](docs/ROADMAP.md) 「개발 단계」 절 참조.

### 핵심 기술 스택

| 계층 | 기술 |
|---|---|
| **Java/Framework** | Java 25, Spring Boot 4.1, Spring Data JPA |
| **DB** | PostgreSQL 18, Flyway (마이그레이션), NUMERIC(28,8) (금융 정밀도) |
| **동시성** | Virtual Threads (`spring.threads.virtual.enabled=true`) |
| **Observability** | Prometheus 메트릭, 구조화 로깅 (Logstash) |
| **보안** | Spring Security, JWT Access/Refresh Token, 로그아웃 |
| **외부 연동** | RestClient + Resilience4j Circuit Breaker (업비트·환율·주식 시세) |
| **프론트엔드** | React + Vite + TypeScript (SPA), Capacitor (하이브리드 앱) — `frontend/` |

### 계층 구조

백엔드는 표준 엔터프라이즈 구조를, 프론트엔드는 별도 디렉터리를 따릅니다:

```
src/main/java/com/allfolio/
  web/         REST 컨트롤러 + dto/ (요청·응답 객체)
  domain/      엔티티 + service/ (비즈니스 로직) + repository/ + exception/
  infra/       security/ (JWT 발급·검증 필터), price/ (외부 시세 클라이언트), logging/
  config/      Spring 빈 설정, 보안 정책

frontend/      React + Vite + TypeScript
```

### Phase 기반 로드맵

| Phase | 초점 | 기술 |
|---|---|---|
| **Phase 1** | 애플리케이션 골격 구축 (백엔드 인증·프론트 라우팅·API 계약) | Spring Security, JWT, React, Vite |
| **Phase 2** | UI/UX 완성(더미 데이터) + 백엔드 자산 CRUD·시뮬레이터 (병렬 트랙) | React 컴포넌트, BigDecimal, In-Memory 시뮬레이터 |
| **Phase 3** (현재) | 실데이터 연동 + 외부 시세 API + Redis 캐시 | Upbit/공공데이터포털(주식)/환율 API, Resilience4j, Redis, Throttling |
| **Phase 4** | SSE 실시간 스트리밍·하이브리드 앱·대용량 부하 | Virtual Threads, SSE, FCM/APNs, k6, Capacitor |

이 표의 Phase 번호는 `docs/ROADMAP.md`의 신규 체계를 따릅니다(기존 "Phase 2 = 외부 시세"였던 이전 체계와 다름 — 대응표는 ROADMAP.md 참조). 자세한 내용은 [`docs/ROADMAP.md`](docs/ROADMAP.md)와 [`docs/PRD.md`](docs/PRD.md) 참조.

---

## 데이터베이스

### 로컬 개발 DB 설정

```bash
# Docker에서 PG 시작
docker compose up -d postgres

# DB 연결 (필요시)
psql -h localhost -U allfolio -d allfolio
```

테스트는 **Testcontainers**(자동 관리형 PG)를 사용하므로 마이그레이션이 매번 검증됩니다.

---

## 규칙 파일 위치

이 CLAUDE.md에는 프로젝트 전체에 항상 필요한 정보만 남아 있다. 특정 파일 종류·폴더를 다룰 때만
필요한 규칙은 아래로 분리돼 있고, 해당 파일을 열면 자동으로 컨텍스트에 로드된다.

| 위치 | 언제 로드되는가 | 내용 |
|---|---|---|
| `.claude/rules/spring-boot-4.md` | `src/main/java`·`src/test/java`의 `.java` 파일 | Spring Boot 4 함정, Virtual Threads, JPA Open-in-View |
| `.claude/rules/testing.md` | `src/test/java`의 `.java` 파일 | 테스트 간헐적 실패 트러블슈팅, `BigDecimal` 비교 규칙 |
| `.claude/rules/financial-precision.md` | `src/main/java`의 `.java` 파일 | `double`/`float` 금지, `NUMERIC(28,8)` 매핑, `PrecisionScale` 재사용 |
| `src/main/java/com/allfolio/domain/CLAUDE.md` | `domain/` 폴더 파일 | 자산·포트폴리오·시뮬레이션 개념, CASH 평단가, 404 컨벤션, 낙관적 잠금, 시뮬레이터 KPI |
| `src/main/java/com/allfolio/web/CLAUDE.md` | `web/` 폴더 파일 | 컨트롤러 `@Validated` 금지 |
| `src/main/java/com/allfolio/infra/price/CLAUDE.md` | `infra/price/` 폴더 파일 | 외부 시세 클라이언트 설계 결정 |
| `src/main/resources/db/migration/CLAUDE.md` | 마이그레이션 폴더 파일 | `V*.sql` 작성 원칙 |
| `frontend/CLAUDE.md` | `frontend/` 폴더 파일 | Vite 프록시 설명 |

## 참고 문서

- **[README](README.md)** — 처음 실행 온보딩 전용 문서(사전 요구사항, 기동 절차, curl 기반 API 흐름). 빌드/테스트 상세·아키텍처 레퍼런스는 이 CLAUDE.md가 담당하도록 역할이 분리돼 있음
- **[PRD](docs/PRD.md)** — 화면·기능 중심 MVP 명세(F001~F010), 화면 경로, 클라이언트 스택, 데이터 모델. API 규격·에러 포맷·성능 KPI·리스크는 다루지 않음
- **[개발 로드맵](docs/ROADMAP.md)** — Phase/Task별 진행 상황, API 규격·에러 포맷·성능 KPI·리스크의 원본(single source of truth), 착수 전 결정 사항, Phase 번호 대응표
- **[디자인 시스템](docs/DESIGN.md)** — 팔레트·타이포·간격 등 디자인 토큰, 화면 골격 규약, 컴포넌트 시각 규약의 단일 출처
- **[E2E 시나리오](docs/E2E_SCENARIOS.md)** — Playwright MCP E2E 시나리오 목록·실행 절차·결과 근거(Task 020, `qa-e2e` 에이전트 소관)
- **[Spring Boot 레퍼런스](https://spring.io/projects/spring-boot)** — 프레임워크 문서
