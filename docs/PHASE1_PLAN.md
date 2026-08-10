# Phase 1 개발 계획 — 자산 CRUD + 시뮬레이터

**기간:** 2주
**목표:** 외부 API·Redis·SSE 없이 순수 도메인 계층과 In-Memory 시뮬레이터를 완성하여, 이후 Phase의 코어 기반 마련
**PRD 참조:** PRD F005(포트폴리오 홈 — Phase 1 부분집합은 본 문서에서 F005a로 정의)·F006(물타기 시뮬레이터), 🗄️ 데이터 모델
**본 문서의 위치:** PRD v2.0.0은 화면·기능 명세만 다룬다. API 규격·에러 포맷·성능 KPI·리스크는 **본 문서가 원본(single source of truth)**이다.

## 진행 현황

| Step | 상태 | 근거 |
|---|---|---|
| 1. 프로젝트 스캐폴딩 | ✅ 완료 | `build.gradle.kts`, `docker-compose.yml`, `application.yml` |
| 2. DB 스키마 & Flyway 마이그레이션 | ✅ 완료 | 커밋 `cf24471`, `V1__init.sql`·`V2__indexes.sql`, `SchemaMigrationTest` 12종 통과 |
| 3. 인증 최소 스켈레톤 | ✅ 완료 (미커밋) | `AuthController`·`JwtIssuer`·`JwtFilter`·`GlobalExceptionHandler`, `AuthIntegrationTest` |
| 4. 자산 CRUD + 포트폴리오 홈(F005a) | ⬜ 예정 | — |
| 5. 물타기 시뮬레이터 | ⬜ 예정 | — |
| 6. 정밀도 & 도메인 테스트 | ⬜ 예정 | — |
| 7. Observability 최소 셋업 | ⬜ 예정 | — |
| 8. 문서 & 마감 | ⬜ 예정 | — |

---

## 범위 확정

### In-Scope (Phase 1)

| 항목 | PRD 참조 |
|---|---|
| 프로젝트 스캐폴딩 (Gradle, Spring Boot, Docker Compose) | PRD 🛠️ 기술 스택 |
| DB 스키마 & Flyway 마이그레이션 (users/assets/holdings/transactions) | PRD 🗄️ 데이터 모델 |
| 인증 최소 스켈레톤 (JWT signup/login, Access Token 15분) | PRD F010(기본 인증) |
| 자산 CRUD API (`POST/GET/PUT/DELETE /v1/assets`) | PRD F001~F004(자산 등록·조회·수정·삭제) |
| 포트폴리오 홈 — 취득원가 기준 (`GET /v1/portfolio`) | PRD F005의 Phase 1 부분집합, 본 문서 Step 4에서 F005a로 정의 |
| 물타기 시뮬레이터 (`POST /v1/simulate/avg-price`, In-Memory) | PRD F006(물타기 시뮬레이터) |
| BigDecimal 정밀도 단위 테스트 | CLAUDE.md 「금융 정밀도」 |
| Observability 최소 셋업 (Prometheus 메트릭, 감사 로그) | 본 문서 자체 정의 |

### Out-of-Scope (뒤 Phase 소관)

| 항목 | 담당 Phase |
|---|---|
| 외부 시세 API (업비트/KIS/환율) | Phase 2 |
| Redis 캐시·INCREX Throttling | Phase 2 |
| 포트폴리오 F005b — 현재가 기반 평가금액·비중(%)·미실현 손익 | Phase 2 |
| `Transactions` CRUD | Phase 2 |
| `/v1/auth/logout`, Refresh Token | Phase 2 |
| SSE 스트리밍·Virtual Thread 부하 검증 | Phase 3 |
| FCM/APNs Push, DeviceTokens | Phase 3 이후 |
| k6 부하 벤치마크 리포트 | Phase 4 |

---

## 완료 기준 (Phase 1 Exit Criteria)

다음 4개가 **모두** 통과해야 Phase 1 완료:

1. **기능 검증** — curl 시퀀스
   ```
   POST /v1/auth/signup → POST /v1/auth/login → JWT 획득
   POST /v1/assets (삼성전자 10주 60,000원) → 201
   GET /v1/assets → 등록 자산 포함 확인
   GET /v1/portfolio → 취득원가 합계 확인 (F005a)
   POST /v1/simulate/avg-price (55,000원 × 5주) → expectedAvgPrice: "58333"
   재조회 시 holdings.avg_price 여전히 60000 (DB 저장 없음 확인)
   ```
2. **테스트** — `./gradlew test` 전체 그린, BigDecimal 정밀도·Optimistic Lock 포함
3. **성능 KPI** — 시뮬레이션 반복 호출(1,000회) 후 Prometheus P99 ≤ 5ms
4. **정적 리뷰** — 소스 전역 `double`/`float` 금융 연산 0건 (grep 확인)

---

## Step별 작업 계획

### Step 1. 프로젝트 스캐폴딩 ✅ 완료

**목표:** 앱이 부팅되고 `/actuator/health`가 `UP`을 반환하는 상태

**구현 결과**

| 파일 | 내용 |
|---|---|
| `settings.gradle.kts` | 프로젝트명 `allfolio` |
| `build.gradle.kts` | Java 25 toolchain, Spring Boot 4.1.0, Gradle 8+ Kotlin DSL |
| `docker-compose.yml` | PostgreSQL 18 (포트 5432), Redis 8.8 주석 처리(Phase 2 대비) |
| `src/main/resources/application.yml` | `spring.threads.virtual.enabled=true`, Flyway, Actuator, Prometheus |
| 패키지 골격 | `com.allfolio.{config,domain,web,infra}` |

**핵심 의존성**

```
implementation: spring-boot-starter-web, -data-jpa, -validation, -actuator, -security
implementation: spring-boot-flyway, flyway-database-postgresql, postgresql
  ※ Spring Boot 4.1은 Flyway 오토컨피규레이션을 flyway-core가 아닌
    org.springframework.boot:spring-boot-flyway 모듈로 분리함.
    flyway-core만 추가하면 FlywayAutoConfiguration이 로드되지 않아
    마이그레이션이 조용히 실행되지 않는다 (Step 2에서 실측 확인).
implementation: micrometer-registry-prometheus
implementation: nimbus-jose-jwt (JWT 발급/검증)
implementation: logstash-logback-encoder
testImplementation: spring-boot-starter-test, spring-boot-starter-webmvc-test, testcontainers(-postgresql), assertj
  ※ Spring Boot 4부터 @SpringBootTest가 MockMvc를 더 이상 자동 제공하지 않는다.
    spring-boot-starter-webmvc-test를 별도로 추가해야 한다 (Step 3에서 실측 확인).
```

**검증:** `./gradlew build` 성공, `docker compose up -d` 후 앱 부팅 `/actuator/health` → `{"status":"UP"}`

---

### Step 2. DB 스키마 & Flyway 마이그레이션 ✅ 완료

**목표:** Testcontainers PG18에서 마이그레이션 성공, `NUMERIC(28,8)` 컬럼 확인

**구현 결과**

`src/main/resources/db/migration/V1__init.sql`

```sql
-- users, assets, holdings, transactions 테이블
-- NUMERIC(28,8), uuidv7() DEFAULT, Optimistic Lock(version)
-- CHECK 제약: asset_type, tx_type, holdings.avg_price > 0, holdings.quantity >= 0
-- holdings.asset_id UNIQUE (종목당 1행)
-- 삭제 전파: assets → holdings/transactions ON DELETE CASCADE
```

`src/main/resources/db/migration/V2__indexes.sql`

```sql
-- idx_assets_user_id
-- idx_transactions_asset_traded
```

> `price_snapshots` 파티셔닝·`device_tokens`는 Phase 2/3에서 추가

**실측 확인된 함정 2건** (CLAUDE.md 「Spring Boot 4 특이사항」에도 기록):

1. Flyway 오토컨피규레이션이 `flyway-core`만으로는 로드되지 않아 마이그레이션이 조용히 건너뛰어짐 → `spring-boot-flyway` 모듈 추가로 해결
2. Testcontainers 1.21.0 고정이 Docker Engine 29+와 API 버전 협상 실패 → Spring Boot 4.1 BOM이 관리하는 2.x로 전환

**검증:** Testcontainers 기반 `SchemaMigrationTest` 12종 통과

---

### Step 3. 인증 최소 스켈레톤 ✅ 완료 (미커밋)

**목표:** signup → login → JWT로 보호 API 호출 가능

**구현 결과**

| 클래스 | 역할 |
|---|---|
| `User` Entity + `UserRepository` | users 테이블 매핑 |
| `AuthController` | `POST /v1/auth/signup`, `POST /v1/auth/login` |
| `AuthService` | 회원가입·로그인 로직. Refresh Token은 Phase 2로 유예 |
| `JwtIssuer` | Access Token 발급·검증 (HS256 대칭키, 15분 TTL). Phase 2+에서 RS256/JWKS 전환 예정 |
| `JwtProperties` | `allfolio.jwt.*` 설정 바인딩 (`@ConfigurationProperties` — yml 값을 타입 안전한 객체로 묶는 스프링 기능) |
| `JwtFilter` | 요청마다 JWT 검증 → `SecurityContextHolder` 주입 |
| `RestAuthenticationEntryPoint` | 인증 실패를 `handlerExceptionResolver`로 위임해 `GlobalExceptionHandler`와 동일한 포맷으로 응답 |
| `GlobalExceptionHandler` | `@ControllerAdvice`. 에러 응답 포맷 단일화 지점 |
| `EmailAlreadyExistsException` / `InvalidCredentialsException` | 도메인 예외 — HTTP 상태 결정을 컨트롤러가 아닌 핸들러 한 곳에 모으기 위함 |
| `MaxUtf8Bytes` | BCrypt의 72바이트 입력 한계를 검증하는 커스텀 Bean Validation 애너테이션 |

**에러 응답 포맷** — 계획에는 없었으나 구현 과정에서 10개 코드로 확정됐다. 응답은 항상 `{code, message, timestamp}` 3필드.

```json
{
  "code": "ASSET_NOT_FOUND",
  "message": "해당 자산을 찾을 수 없습니다.",
  "timestamp": "2026-08-05T10:00:00Z"
}
```

구현된 코드: `EMAIL_ALREADY_EXISTS`(409), `INVALID_CREDENTIALS`(401, 미가입 이메일과 비밀번호 불일치를 동일 코드로 응답해 user enumeration 방지), `UNAUTHORIZED`(401), `VALIDATION_ERROR`(400), `NOT_FOUND`(404), `METHOD_NOT_ALLOWED`(405), `UNSUPPORTED_MEDIA_TYPE`(415), `NOT_ACCEPTABLE`(406), `CLIENT_ERROR`(4xx 포괄), `INTERNAL_ERROR`(500). 자산 도메인에서 쓸 `ASSET_NOT_FOUND`, `HOLDING_CONFLICT`는 Step 4에서 추가한다.

**Phase 1 유예:** Rotating Refresh Token, `/v1/auth/logout`, Per-User Rate Limit, AttributeConverter 암호화

**⚠️ 남은 갭:** Access Token TTL이 15분인데 Refresh Token이 없어 **사용자는 15분마다 재로그인해야 한다.** 이 제품은 실시간 차트(F007)를 띄워두고 지켜보는 사용 패턴을 전제하므로 세션 만료가 핵심 시나리오와 충돌한다. Phase 2 착수 시 최우선 처리 항목.

**검증:** signup/login curl 성공, 인증 없이 자산 API 접근 시 401

---

### Step 4. 자산 CRUD + 포트폴리오 홈 (F001~F004, F005a)

**목표:** 자산 5개 엔드포인트 + 포트폴리오 홈 API 동작, 유저 소유권 격리

**산출물**

| 클래스 | 역할 |
|---|---|
| `Asset` Entity + `AssetRepository` | assets 테이블 매핑 |
| `Holding` Entity (`@Version`) + `HoldingRepository` | holdings 테이블, Optimistic Lock |
| `AssetService` | 자산 생성 시 Holding 동시 생성(단일 트랜잭션), 삭제 cascade |
| `AssetController` | 아래 5개 엔드포인트 |
| `PortfolioService` / `PortfolioController` | `GET /v1/portfolio` — F005a |

**엔드포인트 (본 문서 자체 정의)**

| Method | Path | Status | 비고 |
|---|---|---|---|
| `POST` | `/v1/assets` | 201 | `AssetType`, `currency`, `quantity`, `avgPrice` |
| `GET` | `/v1/assets` | 200 | 커서 페이지네이션 (`limit`, `cursor`) |
| `GET` | `/v1/assets/{id}` | 200 / 404 | 타 유저 접근 시 404 (ID 유출 방지) |
| `PUT` | `/v1/assets/{id}/holdings` | 200 / 409 | Optimistic Lock 충돌 시 `HOLDING_CONFLICT` |
| `DELETE` | `/v1/assets/{id}` | 204 | |
| `GET` | `/v1/portfolio` | 200 | F005a — 아래 참조 |

**F005a — 포트폴리오 홈 (Phase 1 범위)**

PRD F005는 "KRW 환산 평가금액·비중(%)·미실현 손익"을 요구하지만, 이 세 값은 종목 현재가와 USD/KRW 환율(둘 다 외부 API)이 있어야 계산할 수 있다. Phase 1은 외부 API를 다루지 않으므로, F005를 다음과 같이 나눠 취득원가만으로 계산 가능한 부분만 Phase 1에 포함한다.

| 구분 | 내용 | Phase |
|---|---|---|
| **F005a** | 보유 자산 목록 + 종목별 취득원가(`avg_price × quantity`) + 취득원가 합계 | **1 (본 Step)** |
| **F005b** | 현재가 기반 평가금액·비중(%)·미실현 손익 | 2 |

`GET /v1/portfolio` 응답에서 `evaluationKrw`·`unrealizedPnl`·`weight` 필드는 Phase 1에서 `null`.

**Bean Validation 규칙**

- `ticker`: 1~20자, 공백 불가
- `quantity`: `≥ 0`, `NUMERIC(28,8)` 범위
- `avgPrice`: `> 0` (단, CASH 자산 처리는 아래 「착수 전 결정 사항」 참조)

**착수 전 결정 사항** — 코드를 쓰기 전에 정해야 하며, 미룰수록 되돌리기 비싸진다.

| # | 쟁점 | 확인된 사실 | 결정 필요 |
|---|---|---|---|
| 1 | CASH 자산 등록 | `holdings`에 `CHECK (avg_price > 0)` — 현금에는 평단가 개념이 없어 `0`을 넣으면 INSERT 실패 | 화면에서 평단가 입력란 숨기고 서버가 `1` 고정 삽입 / CHECK 제약 완화 중 택1 |
| 2 | `transactions` 기록 여부 | F001(자산 등록)·F003(자산 수정) 어디에도 `transactions` INSERT 경로가 정의돼 있지 않음 | **필수 — 과거 매매 시점 데이터는 사후 소급 생성이 불가능**하므로 Step 4 코딩 전에 결정. 미기록 시 Phase 2에서 이력 화면을 만들 데이터 자체가 없다 |
| 3 | 종목 중복 등록 | `assets`에 `(user_id, ticker)` UNIQUE 없음 — 같은 종목을 여러 번 등록 가능 | 중복 차단 시 `V3__unique_asset_ticker.sql` 추가 필요 |
| 4 | 자산 삭제 시 이력 소실 | `holdings`·`transactions`의 FK가 `ON DELETE CASCADE` — 자산 삭제(F004) 시 거래 이력도 함께 사라짐 | 삭제 확인 팝업 문구에 이력 소실 경고 포함 여부 |

**Phase 1 유예:** F005b(외부 시세 필요), Transactions 조회 API

**검증:** Testcontainers 통합 테스트 (CRUD 전 케이스 + Optimistic Lock 동시성 재현 + 포트폴리오 취득원가 합계 검증)

---

### Step 5. 물타기 시뮬레이터 (F006)

**목표:** DB 저장 없이 In-Memory 가중평균 계산, P99 ≤ 5ms

**핵심 수식**

```
예상평단가 = ((기존평단 × 기존수량) + (신규가 × 신규수량)) / (기존수량 + 신규수량)
```

**예시 검증 케이스:**
```
기존: 평단 60,000원 × 10주 → 600,000
추가: 55,000원 × 5주 → 275,000
예상평단 = 875,000 / 15 = 58,333.33... → HALF_UP, scale 0 → 58,333원
```

**산출물**

| 클래스 | 역할 |
|---|---|
| `SimulationService` | `simulateAveragePrice(assetId, additionalPrice, additionalQty)` |
| `SimulationController` | `POST /v1/simulate/avg-price` |

**스케일 규칙 (CLAUDE.md 「금융 정밀도」)**

| 자산 유형 | 스케일 | RoundingMode |
|---|---|---|
| KRW 주식 | 0 | HALF_UP |
| USD 자산 | 4 | HALF_UP |
| 코인 | 8 | HALF_UP |
| 비중(%) | 2 | HALF_UP |

**응답 (본 문서 자체 정의)**

```json
{
  "currentAvgPrice": "60000.00",
  "expectedAvgPrice": "58333.00",
  "currentWeight": null,
  "expectedWeight": null,
  "calculatedAt": "2026-08-05T10:00:00.003Z"
}
```

> `currentWeight`/`expectedWeight`가 Phase 1에서 `null`인 이유: 비중(%)의 분모는 전체 포트폴리오 평가금액이고, 평가금액은 F005b와 마찬가지로 외부 시세가 있어야 계산된다. Phase 1은 순수 In-Memory 계산(가중평균 평단가)만 제공하고, 비중은 외부 시세 연동 후(Phase 2) 채운다.

**검증**
- 단위 테스트: 위 예시 골든 케이스 포함 경계값 5종
- DB 저장 없음: 시뮬레이션 후 `holdings.version`·`updated_at` 불변 확인, `AUDIT` 로그 없음. 단 대상 holding **조회**는 1회 발생한다 (저장이 없을 뿐 조회는 있음 — CLAUDE.md 「물타기 시뮬레이터」 참조)
- P99 ≤ 5ms: Prometheus `allfolio_simulation_duration_seconds` 히스토그램 P99 확인 (holding 단건 조회 포함한 수치)

---

### Step 6. 정밀도 & 도메인 테스트

**목표:** `./gradlew test` 전체 그린, double 사용 0건

**테스트 클래스 목록**

| 클래스 | 검증 내용 |
|---|---|
| `BigDecimalPrecisionTest` | `0.1 + 0.2 ≠ 0.3` 금융 케이스, 스케일/RoundingMode 규칙 |
| `SimulationServiceTest` | 골든 케이스, 경계값(수량 0, 큰 수, 코인 소수 8자리) |
| `AssetCrudIntegrationTest` | Testcontainers PG18 CRUD 전 케이스 |
| `OptimisticLockingTest` | 병렬 갱신 → `ObjectOptimisticLockingFailureException` |
| `AuthIntegrationTest` | signup/login 흐름, 중복 이메일 등 (Step 3에서 이미 작성) |

**정적 체크:** `grep -r "double\|float" src/main/java` 결과 0건 (금융 연산 한정)

**문서 추가:** `docs/CODE_REVIEW.md` — Phase 1 코드 리뷰 체크리스트 (`double` 금지 등)

---

### Step 7. Observability 최소 셋업

**목표:** Prometheus에서 시뮬레이션 히스토그램 조회 가능, 감사 로그 출력

**산출물**

| 항목 | 상세 |
|---|---|
| Micrometer Histogram | `allfolio_simulation_duration_seconds` — P99 측정 기준 |
| Logback JSON 인코더 | logstash-logback-encoder, MDC: `traceId`, `userId` |
| 감사 로그 마커 | `Holding` 생성/수정/삭제 시 `AUDIT` 마커 (본 문서 자체 정의) |

**Phase 1 유예:** OpenTelemetry Bridge, Grafana Loki/Tempo 파이프라인 (Phase 4)

**인프라 벤더:** 미결정. Phase 1은 벤더 중립 Prometheus registry로 유지하고, 벤더 확정 시 (a) ADOT Collector에 CloudWatch/X-Ray exporter 추가(앱 코드 변경 없음) 또는 (b) `micrometer-registry-cloudwatch` 의존성 추가로 이중 발행 가능. 벤더 결정 시 본 문단을 직접 개정.

**검증:** `/actuator/prometheus`에서 `allfolio_simulation_duration_seconds` 확인, 시뮬레이션 호출 후 카운트 증가

---

### Step 8. 문서 & 마감

**목표:** README만 보고 로컬 실행 가능, Phase 1 완료 체크리스트 작성

**산출물**

| 문서 | 내용 |
|---|---|
| `README.md` | 로컬 실행법 (Docker Compose → Gradle), API curl 예시 |
| `docs/PHASE1_DONE.md` | 완료 체크리스트, KPI 실측 수치, 미해결 이슈 |

**Phase 2 착수 전 선행 확인 사항:**
- Lettuce 클라이언트의 `INCREX` 명령 지원 버전 확인 (미지원 시 Lua Script Fallback 검토)
- Redis 8.8 Testcontainers 이미지 가용 여부 확인
- F005b·F007(실시간 차트)·외부 API 연동의 상세 기술 명세는 본 저장소에 문서로 남아있지 않다. 필요 시 `git show cf24471:docs/PRD.md`로 구 PRD v1.2.0(업비트/KIS WebSocket 연동, SSE 이벤트 스키마, 환율 API, `price_snapshots` 파티셔닝 포함)을 열람해 참고할 것

---

## 핵심 파일 목록

### 구현 완료 (Step 1~3)

```
allfolio/
├── build.gradle.kts
├── settings.gradle.kts
├── docker-compose.yml
├── docs/
│   ├── PRD.md
│   └── PHASE1_PLAN.md                  (본 문서)
└── src/
    ├── main/
    │   ├── java/com/allfolio/
    │   │   ├── AllfolioApplication.java
    │   │   ├── config/
    │   │   │   └── SecurityConfig.java
    │   │   ├── domain/
    │   │   │   ├── User.java
    │   │   │   ├── exception/
    │   │   │   │   ├── EmailAlreadyExistsException.java
    │   │   │   │   └── InvalidCredentialsException.java
    │   │   │   ├── repository/
    │   │   │   │   └── UserRepository.java
    │   │   │   └── service/
    │   │   │       └── AuthService.java
    │   │   ├── web/
    │   │   │   ├── AuthController.java
    │   │   │   ├── GlobalExceptionHandler.java
    │   │   │   └── dto/
    │   │   │       ├── ErrorResponse.java
    │   │   │       ├── LoginRequest.java
    │   │   │       ├── MaxUtf8Bytes.java
    │   │   │       ├── SignupRequest.java
    │   │   │       └── TokenResponse.java
    │   │   └── infra/
    │   │       └── security/
    │   │           ├── JwtFilter.java
    │   │           ├── JwtIssuer.java
    │   │           ├── JwtProperties.java
    │   │           └── RestAuthenticationEntryPoint.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__init.sql
    │           └── V2__indexes.sql
    └── test/
        └── java/com/allfolio/
            ├── AbstractIntegrationTest.java
            ├── AuthIntegrationTest.java
            └── SchemaMigrationTest.java
```

### 미구현 (Step 4~8 예정)

```
    ├── domain/
    │   ├── Asset.java
    │   ├── Holding.java
    │   ├── Transaction.java
    │   ├── repository/
    │   │   ├── AssetRepository.java
    │   │   └── HoldingRepository.java
    │   └── service/
    │       ├── AssetService.java
    │       ├── PortfolioService.java
    │       └── SimulationService.java
    ├── web/
    │   ├── AssetController.java
    │   ├── PortfolioController.java
    │   └── SimulationController.java
    └── test/
        ├── BigDecimalPrecisionTest.java
        ├── SimulationServiceTest.java
        ├── AssetCrudIntegrationTest.java
        └── OptimisticLockingTest.java
```

---

## 기술 스택 (Phase 1 사용 범위)

| Layer | 기술 | 버전 | Phase 1 사용 여부 |
|---|---|---|---|
| Language | Java | 25 LTS | ✅ |
| Framework | Spring Boot | 4.1.0 | ✅ |
| Concurrency | Virtual Thread | `spring.threads.virtual.enabled=true` | ✅ (활성화만, 부하 검증은 Phase 3) |
| ORM | Spring Data JPA (Hibernate 7) | 7.x | ✅ |
| RDBMS | PostgreSQL | 18 | ✅ |
| Cache | Redis | 8.8 | ❌ (Phase 2) |
| Schema Migration | Flyway | 11.x | ✅ |
| Observability | Micrometer + Prometheus | — | ✅ (최소) |
| Testing | JUnit 5 + Testcontainers + WireMock | — | ✅ (WireMock은 Phase 2) |
| Build | Gradle 8+ (Kotlin DSL) + Docker | — | ✅ |

---

## 리스크 & 완화 전략 (Phase 1 관련)

| 리스크 | 완화 |
|---|---|
| BigDecimal 스케일 규칙 누락 | CLAUDE.md 「금융 정밀도」 스케일 표를 `BigDecimalPrecisionTest`로 상수화, 모든 서비스 로직에 재사용 |
| Optimistic Lock 고빈도 충돌 | Phase 1은 유저 수 적으므로 단순 `@Version`으로 충분. 클라이언트 재시도 지침을 API 응답에 포함 |
| CASH 자산의 `avg_price` CHECK 제약 충돌 | Step 4 착수 전 결정 사항 #1 참조 |
| `transactions` 미기록 시 이력 소급 불가 | Step 4 착수 전 결정 사항 #2 참조 — 코딩 전 확정 필수 |

**해소된 리스크 (Step 2에서 실측 확인 완료):**

- Spring Boot 4.1.0 + Java 25 호환성 — 정상 동작 확인
- `uuidv7()` PG18 함수 지원 — Testcontainers PG18 이미지에서 확인됨
- Flyway 오토컨피규레이션 누락, Testcontainers 버전 협상 실패 — Step 2 본문의 "실측 확인된 함정" 참조
