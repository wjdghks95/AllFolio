# AllFolio 개발 로드맵

**최종 수정:** 2026-08-12
**본 문서의 위치:** `docs/PRD.md`가 화면·기능 명세(무엇을 만드는가)를 다루는 반면, 본 문서는 Phase/Task 진행 상황·API 규격·에러 포맷·성능 KPI·리스크의 **single source of truth**(언제·어떤 순서로·어떤 규격으로 만드는가)이다. 기존 `docs/PHASE1_PLAN.md`(Phase 1 백엔드만 다루던 문서)를 대체·흡수하며, Phase 2~4와 프론트엔드 트랙을 함께 포함한다.

## 개요

AllFolio는 증권사·거래소·은행 앱을 3개 이상 따로 쓰며 전체 자산 비중을 파악하지 못하는 개인 투자자를 위한 통합 자산 조회 서비스다.

- **통합 포트폴리오 조회**: 주식·코인·현금을 KRW 기준 한 화면에서 평가금액·비중·손익으로 확인
- **물타기(추가 매수) 시뮬레이터**: 추가 매수 시 예상 평단가를 즉시 계산 (서비스의 핵심 차별화 가치)
- **실시간 시세 차트**: 내 평단가를 겹쳐 표시해 손익 상황을 직관적으로 파악

## 기술 스택

### 백엔드

| 계층 | 기술 |
|---|---|
| Language/Framework | Java 25, Spring Boot 4.1 |
| DB | PostgreSQL 18, Flyway, NUMERIC(28,8) |
| 동시성 | Virtual Threads |
| Observability | Prometheus, 구조화 로깅(Logstash) |
| 보안 | Spring Security, JWT |

### 프론트엔드

| 계층 | 기술 |
|---|---|
| 프레임워크 | React + Vite + TypeScript (SPA) |
| 저장소 위치 | 루트 `frontend/` (백엔드와 같은 저장소 — 모노레포) |
| 모바일 | Capacitor (WebView 하이브리드) |

**선택 근거**

- **Vite**: 개발 중엔 수정된 파일만 즉시 반영(HMR)하고, 배포 시엔 `dist/`에 정적 파일로 뽑아낸다. Capacitor가 "정적 파일 묶음을 앱 WebView에 넣는" 방식이라 `dist/`가 변환 없이 그대로 입력이 된다.
- **React 단독 SPA vs Next.js**: Next.js를 Capacitor에 넣으려면 `output: 'export'`로 정적화해야 하는데, 그 순간 Server Component 등 핵심 이점을 잃고 설정만 늘어난다. Spring Boot가 이미 API 서버라 SSR/BFF 계층도 중복이다.
- **모노레포**: API 계약이 아직 안 굳었고 1인 개발이다. "응답에 필드 추가 + 화면 반영"을 한 커밋으로 묶어야 롤백이 가능하다. 저장소를 나누면 어떤 프론트 커밋이 어떤 백엔드 커밋과 짝인지 추적할 수단이 사라진다.
- **배포 방식은 미결정**: 개발 중엔 Vite 개발 서버(5173)가 `/v1/*`만 8080(Spring Boot)으로 프록시하므로 브라우저 입장에서 같은 출처로 보여 CORS 설정이 필요 없다. 운영에서 JAR 하나로 합칠지 분리 호스팅할지는 Task 029에서 정한다.

## 개발 워크플로우

1. **작업 계획** — 기존 코드베이스 현재 상태 파악 후 본 문서 갱신, 우선순위 작업은 마지막 완료 작업 다음에 삽입
2. **작업 구현** — Task 단위로 구현, API/비즈니스 로직은 통합 테스트(Testcontainers) 또는 Task 020부터는 Playwright MCP E2E로 검증
3. **로드맵 업데이트** — 완료된 Task를 ✅로 표시

이 프로젝트는 1인 개발 규모라 Task별 개별 파일(`/tasks/XXX-*.md`)은 두지 않는다. 아래 체크리스트가 상세 명세를 겸한다.

---

## Phase 번호 대응표

이전에는 `PHASE1_PLAN.md`(Phase 1)와 `CLAUDE.md`(Phase 2~4 개요)가 별도 번호 체계를 썼다. 본 문서는 템플릿 4단계(골격 → UI/UX 완성 → 핵심 기능 → 고급/최적화)로 재편했으므로, 기존 커밋 메시지("✨ feat: Phase 1 Step 3")나 소스 주석을 읽을 때 아래 표로 번역한다.

| 기존 표기 | 새 표기 |
|---|---|
| Phase 1 Step 1 (스캐폴딩) | Phase 1 / Task 001 |
| Phase 1 Step 2 (DB 스키마) | Phase 1 / Task 002 |
| Phase 1 Step 3 (JWT 인증) | Phase 1 / Task 003 |
| Phase 1 Step 4 (자산 CRUD + F005a) | Phase 2 / Task 012, 013 |
| Phase 1 Step 5 (시뮬레이터) | Phase 2 / Task 015 |
| Phase 1 Step 6 (정밀도 테스트) | Phase 2 / Task 016 |
| Phase 1 Step 7 (Observability) | Phase 2 / Task 014 |
| Phase 1 Step 8 (문서 마감) | Phase 2 / Task 017 |
| 기존 Phase 2 (외부 시세·Redis·F005b) | Phase 3 / Task 021~024 |
| 기존 Phase 3 (SSE·Virtual Thread 부하) | Phase 4 / Task 025, 028 |
| 기존 Phase 4 (Push·k6·하이브리드 앱) | Phase 4 / Task 026~029 |

소스 코드 주석은 Phase 번호가 아닌 **Task 번호**로 표기한다. Task 번호는 001부터 연속된 고유값이라 Phase 재편과 무관하게 안정적이다.

**예외**: `V1__init.sql`의 "Phase 1 Step 2", "Phase 2/3에서 추가" 같은 구 표기는 의도적으로 그대로 유지한다. Flyway는 마이그레이션 파일 전체 내용으로 체크섬을 계산하므로, 이미 적용된 파일의 주석 한 글자만 바꿔도 기존 DB에서 부팅 시 checksum mismatch로 실패한다. 이 파일을 읽을 때는 위 대응표로 해석할 것.

---

## 개발 단계

### Phase 1: 애플리케이션 골격 구축

양쪽 트랙(프론트/백엔드)이 서로를 기다리지 않고 출발할 수 있는 상태를 만드는 단계.

- **Task 001: 백엔드 프로젝트 스캐폴딩 및 실행 환경 구축** ✅ — 완료
  - ✅ Gradle Kotlin DSL, Spring Boot 4.1.0, Java 25 toolchain
  - ✅ Docker Compose (PostgreSQL 18)
  - ✅ `spring.threads.virtual.enabled=true`
  - ✅ `/actuator/health` → `UP`

- **Task 002: DB 스키마 설계 및 Flyway 마이그레이션** ✅ — 완료
  - ✅ `V1__init.sql` — users/assets/holdings/transactions, `NUMERIC(28,8)`, `uuidv7()` PK, `version` 낙관적 잠금 컬럼
  - ✅ `V2__indexes.sql` — `idx_assets_user_id`, `idx_transactions_asset_traded`
  - ✅ Testcontainers 기반 `SchemaMigrationTest` 12종 통과

- **Task 003: JWT 인증 스켈레톤 및 전역 에러 응답 포맷 확정 (F010)** ✅ — 완료
  - ✅ `AuthController` (`POST /v1/auth/signup`, `POST /v1/auth/login`)
  - ✅ `JwtIssuer` (HS256, Access Token 15분)
  - ✅ `JwtFilter`, `GlobalExceptionHandler`, 에러 코드 10종
  - ✅ `AuthIntegrationTest` 24종
  - 인증은 제품 기능이 아니라 보안 필터 체인과 에러 포맷 계약을 세우는 인프라라 골격 단계에 둔다.
  - ⚠️ 남은 갭: Refresh Token 부재로 15분마다 재로그인 필요 → Task 019에서 해소

- **Task 004: 프론트엔드 프로젝트 셋업 및 라우팅 골격** — 우선순위
  - `frontend/`에 React + Vite + TypeScript 초기화
  - 5개 라우트(`/login`, `/signup`, `/portfolio`, `/assets/new`, `/assets/:id`)의 빈 페이지 파일
  - 공통 레이아웃·헤더, 인증 가드 골격 (비로그인 접근 시 `/login` 리디렉션)
  - Vite dev server 프록시 설정 (`/v1/*` → `localhost:8080`)
  - `.gitignore`에 `node_modules` 추가
  - 화면 내용 없이 이동 흐름만 완성해 사용자 여정을 먼저 체험한다.

- **Task 005: 백엔드 도메인 엔티티·리포지토리·DTO 타입 정의** — 우선순위
  - `Asset`/`Holding`(`@Version`)/`Transaction` 엔티티
  - `AssetRepository`/`HoldingRepository`
  - 요청·응답 DTO
  - 비즈니스 로직 없이 타입만. `ddl-auto: validate`라 엔티티-스키마 불일치 시 부팅이 실패하므로, 매핑 정합성을 로직과 분리해 먼저 확인하는 편이 원인 추적이 쉽다.

- **Task 006: API 계약 확정 및 미결정 사항 4건 해소** — 우선순위
  - 신규 엔드포인트 7종 규격 확정 (자산 5종·포트폴리오·시뮬레이터, 아래 「API 규격」 절 — 인증 2종은 Task 003에서 이미 구현)
  - `ASSET_NOT_FOUND`·`HOLDING_CONFLICT` 에러 코드 추가
  - 프론트용 TypeScript 타입 및 더미 응답 픽스처 작성
  - 아래 「착수 전 결정 사항」 4건 확정
  - 이 Task가 Phase 2 병렬 트랙의 출발점이다. 미결정 사항 중 `transactions` 기록 여부는 과거 매매 시점 데이터를 사후 소급 생성할 수 없어 Task 012 착수 전 반드시 확정해야 한다.

### Phase 2: UI/UX 완성 + 백엔드 도메인 구현 (병렬 2트랙)

두 트랙 모두 Task 006(API 계약)을 선행 조건으로 한다.

**2-A. 프론트엔드 UI (더미 데이터로 완성)**

- **Task 007: 공통 컴포넌트 및 디자인 시스템 구축**
  - 버튼·입력·폼 검증·카드·모달·토스트
  - 반응형 기준
  - 금액 표기 포맷터 (문자열로 받은 금액을 정밀도 손실 없이 렌더링)

- **Task 008: 인증 화면 구현 (F010)**
  - 로그인·회원가입 폼, 입력 형식 검증, 에러 메시지 표시

- **Task 009: 포트폴리오 홈 화면 구현 (F005)**
  - 자산 목록, 평가금액·비중·손익 칼럼, 전체 합계, "자산 등록" 버튼

- **Task 010: 자산 등록 화면 구현 (F001)**
  - 유형(STOCK/COIN/CASH)·티커·통화·수량·평단가 입력
  - CASH 선택 시 평단가란 처리는 Task 006 결정 #1에 종속

- **Task 011: 자산 상세 화면 구현 (F002·F003·F004·F006·F007)**
  - 상세 정보, 차트 영역(정적 더미), 물타기 시뮬레이터 폼, 수정 폼, 삭제 확인 팝업
  - 삭제 확인 팝업 문구는 「착수 전 결정 사항」 #4(삭제 시 이력 소실)에 종속

**2-B. 백엔드 도메인 로직**

- **Task 012: 자산 CRUD API 구현 (F001~F004)**
  - 5개 엔드포인트, 유저 소유권 격리(타 유저 자산은 403이 아닌 **404** — ID 유출 방지)
  - 자산 생성 시 Holding 동시 생성 (단일 트랜잭션)
  - 자산 등록 로직은 「착수 전 결정 사항」 #3(종목 중복 등록)에 종속

- **Task 013: 포트폴리오 홈 API 구현 (F005a)**
  - `GET /v1/portfolio`, 취득원가(`avg_price × quantity`) 기준
  - 평가금액·비중·손익은 외부 시세가 필요하므로 `null` 반환 (Task 023에서 채움)

- **Task 014: Observability 최소 셋업**
  - Micrometer 히스토그램 `allfolio_simulation_duration_seconds`
  - `logback-spring.xml` JSON 인코더 (의존성은 있으나 설정 파일 미존재)
  - MDC `traceId`/`userId`, `AUDIT` 마커
  - Task 015의 P99를 측정할 수단이므로 시뮬레이터보다 먼저 둔다.

- **Task 015: 물타기 시뮬레이터 구현 (F006)**
  - `POST /v1/simulate/avg-price`, In-Memory 가중평균, DB 쓰기 없음
  - P99 ≤ 5ms

- **Task 016: 금융 정밀도 및 도메인 통합 테스트**
  - `BigDecimalPrecisionTest`, `SimulationServiceTest`, `AssetCrudIntegrationTest`, `OptimisticLockingTest`
  - `double`/`float` 0건 정적 검사

- **Task 017: MVP 로컬 실행 문서화**
  - `README.md` 신규 작성 (현재 저장소에 없음)
  - 백엔드·프론트 동시 실행법, curl 시퀀스

### Phase 3: 핵심 기능 구현 (실데이터 연동 및 외부 시세)

- **Task 018: 프론트–백엔드 실데이터 연동**
  - 더미 데이터를 실제 API 호출로 교체
  - JWT 저장·주입, 401 처리, 로딩·에러 상태

- **Task 019: 인증 강화 — Refresh Token 및 로그아웃 (F010)**
  - Task 003의 남은 갭 해소. 실시간 차트(F007)를 띄워두는 사용 패턴과 15분 만료가 충돌하므로 연동 직후 처리

- **Task 020: E2E 통합 테스트 (Playwright MCP)**
  - 전체 사용자 여정(로그인 → 자산 등록 → 포트폴리오 → 상세 → 시뮬레이션 → 수정/삭제)
  - 에러·엣지 케이스

- **Task 021: 외부 시세 API 연동**
  - Upbit/KIS/환율, Circuit Breaker + Fallback, WireMock 기반 테스트

- **Task 022: Redis 캐시 및 요청 Throttling**
  - Redis 8.8. Stale 응답은 `isStale: true`/`PRICE_STALE` 206으로 명시
  - Lettuce `INCREX` 지원 여부 착수 전 실측 필요

- **Task 023: 포트폴리오 평가금액·비중·손익 (F005b)**
  - Task 013의 `null` 필드 채움 + Task 009 화면 반영
  - 비중 스케일 2, HALF_UP

- **Task 024: 거래 이력(Transactions) API**
  - Task 006 결정 #2 결과에 종속

### Phase 4: 고급 기능 및 최적화

- **Task 025: SSE 실시간 시세 스트리밍 및 실시간 차트 (F007)**
  - 백엔드 SSE + 프론트 차트, 내 평단가 수평선
  - `price_snapshots` 파티셔닝
  - 이벤트 스키마는 구 PRD v1.2.0 §8.3 참조 필요 (`git show cf24471:docs/PRD.md`)

- **Task 026: 푸시 알림 (FCM/APNs)**
  - `device_tokens` 테이블, `revoked_at IS NULL` 부분 인덱스

- **Task 027: Capacitor 하이브리드 앱 패키징**
  - Vite `dist/`를 WebView에 탑재
  - 앱은 `capacitor://` 출처에서 도는 별도 origin이라 개발 중 프록시로 우회하던 CORS 설정이 여기서 실제로 필요해진다.

- **Task 028: 부하 검증 및 성능 튜닝**
  - Virtual Thread 1,000+ 동시 SSE 커넥션, k6 벤치마크

- **Task 029: 배포 파이프라인 및 운영 관측 체계**
  - CI/CD, 프론트 배포 방식 확정(JAR 통합 vs 분리 호스팅)
  - OpenTelemetry Bridge, Grafana Loki/Tempo
  - 인프라 벤더 미결정 유지 — 벤더 확정 시 (a) ADOT Collector에 exporter 추가(앱 코드 변경 없음) 또는 (b) `micrometer-registry-cloudwatch` 의존성 추가로 이중 발행 가능

---

## API 규격

| Method | Path | Status | 비고 |
|---|---|---|---|
| `POST` | `/v1/auth/signup` | 201 / 409 | 가입 성공 시 JWT 자동 발급, 이메일 중복 시 `EMAIL_ALREADY_EXISTS` (F010, Task 003) |
| `POST` | `/v1/auth/login` | 200 / 401 | 실패 시 `INVALID_CREDENTIALS` (F010, Task 003) |
| `POST` | `/v1/assets` | 201 | `AssetType`, `currency`, `quantity`, `avgPrice` |
| `GET` | `/v1/assets` | 200 | 커서 페이지네이션 (`limit` 기본 20/max 100, `cursor`) |
| `GET` | `/v1/assets/{id}` | 200 / 404 | 타 유저 접근 시 404 (ID 유출 방지) |
| `PUT` | `/v1/assets/{id}/holdings` | 200 / 409 | Optimistic Lock 충돌 시 `HOLDING_CONFLICT` |
| `DELETE` | `/v1/assets/{id}` | 204 | |
| `GET` | `/v1/portfolio` | 200 | Task 013 범위(F005a)는 취득원가만, `evaluationKrw`·`unrealizedPnl`·`weight`는 `null` |
| `POST` | `/v1/simulate/avg-price` | 200 | DB 저장 없음 |

**Bean Validation 규칙**
- `ticker`: 1~20자, 공백 불가
- `quantity`: `≥ 0`, `NUMERIC(28,8)` 범위
- `avgPrice`: `> 0` (CASH 자산 처리는 「착수 전 결정 사항」 #1 참조)

**금액·수량은 JSON에서 문자열로 직렬화** (부동소수 손실 방지)

### 시뮬레이터 응답 예시

```json
{
  "currentAvgPrice": "60000",
  "expectedAvgPrice": "58333",
  "currentWeight": null,
  "expectedWeight": null,
  "calculatedAt": "2026-08-05T10:00:00.003Z"
}
```

**골든 케이스**: 기존 평단 60,000원 × 10주 + 추가 55,000원 × 5주 → `(600,000 + 275,000) / 15 = 58,333.33...` → HALF_UP, scale 0 → **58,333원**

`currentWeight`/`expectedWeight`는 포트폴리오 내 비중(%)이다. 분모가 전체 포트폴리오 평가금액인데, 평가금액은 외부 시세가 있어야 계산되므로(F005b) Phase 1~2에서는 `null`이고 Task 023(외부 시세 연동 후) 이후 채워진다.

---

## 에러 응답 포맷

응답은 항상 `{code, message, timestamp}` 3필드.

```json
{
  "code": "ASSET_NOT_FOUND",
  "message": "해당 자산을 찾을 수 없습니다.",
  "timestamp": "2026-08-05T10:00:00Z"
}
```

**구현된 코드 (Task 003)**: `EMAIL_ALREADY_EXISTS`(409), `INVALID_CREDENTIALS`(401, user enumeration 방지를 위해 미가입 이메일과 비밀번호 불일치를 동일 코드로 응답), `UNAUTHORIZED`(401), `VALIDATION_ERROR`(400), `NOT_FOUND`(404), `METHOD_NOT_ALLOWED`(405), `UNSUPPORTED_MEDIA_TYPE`(415), `NOT_ACCEPTABLE`(406), `CLIENT_ERROR`(4xx 포괄), `INTERNAL_ERROR`(500)

**Task 006에서 추가**: `ASSET_NOT_FOUND`, `HOLDING_CONFLICT`

---

## 금융 정밀도 규칙

| 자산 유형 | 스케일 | RoundingMode |
|---|---|---|
| KRW 주식 | 0 | HALF_UP |
| USD 자산 | 4 | HALF_UP |
| 코인 | 8 | HALF_UP |
| 비중(%) | 2 | HALF_UP |

모든 금융 컬럼은 `NUMERIC(28,8)` (BigDecimal 1:1 매핑). `double`/`float` 절대 금지.

---

## 성능 KPI

- **시뮬레이터 응답시간 P99**: ≤ 5ms (1,000회 반복 호출, holding 단건 조회 포함한 수치, JVM 워밍업 후 검증)
- **메트릭**: `allfolio_simulation_duration_seconds` (Prometheus 히스토그램)

---

## 착수 전 결정 사항 (Task 006에서 확정)

| # | 쟁점 | 확인된 사실 | 결정 필요 |
|---|---|---|---|
| 1 | CASH 자산 등록 | `holdings`에 `CHECK (avg_price > 0)` — 현금에는 평단가 개념이 없어 `0`을 넣으면 INSERT 실패 | 화면에서 평단가 입력란 숨기고 서버가 `1` 고정 삽입 / CHECK 제약 완화 중 택1 |
| 2 | `transactions` 기록 여부 | F001(자산 등록)·F003(자산 수정) 어디에도 `transactions` INSERT 경로가 정의돼 있지 않음 | **필수 — 과거 매매 시점 데이터는 사후 소급 생성이 불가능**하므로 Task 012 코딩 전 결정. 미기록 시 Task 024(거래 이력 API)를 만들 데이터 자체가 없다 |
| 3 | 종목 중복 등록 | `assets`에 `(user_id, ticker)` UNIQUE 없음 — 같은 종목을 여러 번 등록 가능 | 중복 차단 시 `V3__unique_asset_ticker.sql` 추가 필요 |
| 4 | 자산 삭제 시 이력 소실 | `holdings`·`transactions`의 FK가 `ON DELETE CASCADE` — 자산 삭제(F004) 시 거래 이력도 함께 사라짐 | 삭제 확인 팝업 문구에 이력 소실 경고 포함 여부 |

---

## 리스크 & 완화 전략

| 리스크 | 완화 |
|---|---|
| BigDecimal 스케일 규칙 누락 | 위 「금융 정밀도 규칙」 표를 `BigDecimalPrecisionTest`로 상수화, 모든 서비스 로직에 재사용 |
| Optimistic Lock 고빈도 충돌 | 유저 수 적은 초기 단계이므로 단순 `@Version`으로 충분. 클라이언트 재시도 지침을 API 응답에 포함 |
| CASH 자산의 `avg_price` CHECK 제약 충돌 | 「착수 전 결정 사항」 #1 참조 |
| `transactions` 미기록 시 이력 소급 불가 | 「착수 전 결정 사항」 #2 참조 — 코딩 전 확정 필수 |
| 종목 중복 등록으로 인한 데이터 정합성 저하 | 「착수 전 결정 사항」 #3 참조 — 중복 차단 시 Task 012 착수 전 `V3__unique_asset_ticker.sql` 필요 |
| 자산 삭제 시 거래 이력 소실 (`ON DELETE CASCADE`) | 「착수 전 결정 사항」 #4 참조 — Task 011 삭제 확인 팝업 문구로 최소 완화 |
| 프론트-백엔드 API 계약 어긋남 | Task 006에서 TypeScript 타입과 더미 픽스처를 계약의 단일 출처로 삼아 양쪽이 참조 |

**해소된 리스크 (Task 002에서 실측 확인 완료)**
- Spring Boot 4.1.0 + Java 25 호환성 — 정상 동작 확인
- `uuidv7()` PG18 함수 지원 — Testcontainers PG18 이미지에서 확인됨
- Flyway 오토컨피규레이션 누락, Testcontainers 버전 협상 실패 — `CLAUDE.md` 「Spring Boot 4 특이사항」 참조

---

## Phase 전환 시 선행 확인 사항

**Phase 3 착수 전 (Task 021~022 관련)**
- Lettuce 클라이언트의 `INCREX` 명령 지원 버전 확인 (미지원 시 Lua Script Fallback 검토)
- Redis 8.8 Testcontainers 이미지 가용 여부 확인
- F005b·F007(실시간 차트)·외부 API 연동의 상세 기술 명세는 본 저장소에 문서로 남아있지 않다. 필요 시 `git show cf24471:docs/PRD.md`로 구 PRD v1.2.0(업비트/KIS WebSocket 연동, SSE 이벤트 스키마, 환율 API, `price_snapshots` 파티셔닝 포함)을 열람해 참고할 것

**Task 004 착수 시**
- 프론트엔드는 `senior-frontend` 에이전트가 담당한다(컴포넌트·라우팅·상태·API 클라이언트·컴포넌트 테스트). Task 020의 Playwright MCP E2E는 착수 시점에 별도 QA 에이전트를 신설한다.
