# Phase 1 개발 계획 — 자산 CRUD + 시뮬레이터

**기간:** 2주  
**목표:** 외부 API·Redis·SSE 없이 순수 도메인 계층과 In-Memory 시뮬레이터를 완성하여, 이후 Phase의 코어 기반 마련  
**PRD 참조:** §E Development Milestones, §5 FR-01/FR-02, §7 Data Model, §8 API Spec

---

## 범위 확정

### In-Scope (Phase 1)

| 항목 | PRD 참조 |
|---|---|
| 프로젝트 스캐폴딩 (Gradle, Spring Boot, Docker Compose) | §4 Tech Stack |
| DB 스키마 & Flyway 마이그레이션 (users/assets/holdings/transactions) | §7 Data Model |
| 인증 최소 스켈레톤 (JWT signup/login, Access Token 15분) | §6.7 Security |
| 자산 CRUD API (`POST/GET/PUT/DELETE /v1/assets`) | §8.1 REST API |
| 물타기 시뮬레이터 (`POST /v1/simulate/avg-price`, In-Memory) | §5 FR-02 |
| BigDecimal 정밀도 단위 테스트 | §6.1 Precision |
| Observability 최소 셋업 (Prometheus 메트릭, 감사 로그) | §6.8 |

### Out-of-Scope (뒤 Phase 소관)

| 항목 | 담당 Phase |
|---|---|
| 외부 시세 API (업비트/KIS/환율) | Phase 2 |
| Redis 캐시·INCREX Throttling | Phase 2 |
| `GET /v1/portfolio` 통합 조회 | Phase 2 |
| `Transactions` CRUD | Phase 2 |
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
   POST /v1/simulate/avg-price (55,000원 × 5주) → expectedAvgPrice: "58333"
   재조회 시 holdings.avg_price 여전히 60000 (DB 저장 없음 확인)
   ```
2. **테스트** — `./gradlew test` 전체 그린, BigDecimal 정밀도·Optimistic Lock 포함
3. **성능 KPI** — 시뮬레이션 반복 호출(1,000회) 후 Prometheus P99 ≤ 5ms
4. **정적 리뷰** — 소스 전역 `double`/`float` 금융 연산 0건 (grep 확인)

---

## Step별 작업 계획

### Step 1. 프로젝트 스캐폴딩

**목표:** 앱이 부팅되고 `/actuator/health`가 `UP`을 반환하는 상태

**산출물**

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
implementation: flyway-core, flyway-database-postgresql, postgresql
implementation: micrometer-registry-prometheus
implementation: nimbus-jose-jwt (JWT 발급/검증)
implementation: logstash-logback-encoder
testImplementation: spring-boot-starter-test, testcontainers(-postgresql), assertj
```

**검증:** `./gradlew build` 성공, `docker compose up -d` 후 앱 부팅 `/actuator/health` → `{"status":"UP"}`

---

### Step 2. DB 스키마 & Flyway 마이그레이션

**목표:** Testcontainers PG18에서 마이그레이션 성공, `NUMERIC(28,8)` 컬럼 확인

**산출물**

`src/main/resources/db/migration/V1__init.sql`

```sql
-- users, assets, holdings, transactions 테이블
-- NUMERIC(28,8), uuidv7() DEFAULT, Optimistic Lock(version)
-- CHECK 제약: asset_type, tx_type
-- holdings.asset_id UNIQUE (종목당 1행)
```

`src/main/resources/db/migration/V2__indexes.sql`

```sql
-- idx_assets_user_id
-- idx_transactions_asset_traded
```

> `price_snapshots` 파티셔닝·`device_tokens`는 Phase 2/3에서 추가

**검증:** Testcontainers 기반 마이그레이션 테스트 통과

---

### Step 3. 인증 최소 스켈레톤

**목표:** signup → login → JWT로 보호 API 호출 가능

**산출물**

| 클래스 | 역할 |
|---|---|
| `User` Entity + `UserRepository` | users 테이블 매핑 |
| `AuthController` | `POST /v1/auth/signup`, `POST /v1/auth/login` |
| `JwtIssuer` | Access Token 발급 (RS256 또는 HS256, 15분 TTL) |
| `JwtFilter` | 요청마다 JWT 검증 → `SecurityContextHolder` 주입 |
| `GlobalExceptionHandler` | `@ControllerAdvice`, PRD §8.2 에러 포맷 |

**PRD §8.2 에러 응답 포맷**

```json
{
  "code": "ASSET_NOT_FOUND",
  "message": "해당 자산을 찾을 수 없습니다.",
  "timestamp": "2026-08-05T10:00:00Z"
}
```

**Phase 1 유예:** Rotating Refresh Token, Per-User Rate Limit, AttributeConverter 암호화

**검증:** signup/login curl 성공, 인증 없이 자산 API 접근 시 401

---

### Step 4. 자산 CRUD (FR-01 도메인 계층)

**목표:** 자산 5개 엔드포인트 모두 동작, 유저 소유권 격리

**산출물**

| 클래스 | 역할 |
|---|---|
| `Asset` Entity + `AssetRepository` | assets 테이블 매핑 |
| `Holding` Entity (`@Version`) + `HoldingRepository` | holdings 테이블, Optimistic Lock |
| `AssetService` | 자산 생성 시 Holding 동시 생성(단일 트랜잭션), 삭제 cascade |
| `AssetController` | 아래 5개 엔드포인트 |

**엔드포인트 (PRD §8.1)**

| Method | Path | Status | 비고 |
|---|---|---|---|
| `POST` | `/v1/assets` | 201 | `AssetType`, `currency`, `quantity`, `avgPrice` |
| `GET` | `/v1/assets` | 200 | 커서 페이지네이션 (`limit`, `cursor`) |
| `GET` | `/v1/assets/{id}` | 200 / 404 | 타 유저 접근 시 404 (ID 유출 방지) |
| `PUT` | `/v1/assets/{id}/holdings` | 200 / 409 | Optimistic Lock 충돌 시 `HOLDING_CONFLICT` |
| `DELETE` | `/v1/assets/{id}` | 204 | |

**Bean Validation 규칙**

- `ticker`: 1~20자, 공백 불가
- `quantity`: `≥ 0`, `NUMERIC(28,8)` 범위
- `avgPrice`: `> 0`

**Phase 1 유예:** `GET /v1/portfolio` (외부 시세 필요), Transactions CRUD

**검증:** Testcontainers 통합 테스트 (CRUD 전 케이스 + Optimistic Lock 동시성 재현)

---

### Step 5. 물타기 시뮬레이터 (FR-02)

**목표:** DB 저장 없이 In-Memory 가중평균 계산, P99 ≤ 5ms

**핵심 수식 (PRD §5 FR-02)**

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

**스케일 규칙 (PRD §6.1)**

| 자산 유형 | 스케일 | RoundingMode |
|---|---|---|
| KRW 주식 | 0 | HALF_UP |
| USD 자산 | 4 | HALF_UP |
| 코인 | 8 | HALF_UP |
| 비중(%) | 2 | HALF_UP |

**응답 (PRD §8.1)**

```json
{
  "currentAvgPrice": "60000.00",
  "expectedAvgPrice": "58333.00",
  "currentWeight": null,
  "expectedWeight": null,
  "calculatedAt": "2026-08-05T10:00:00.003Z"
}
```

> `currentWeight`/`expectedWeight`는 외부 시세 연동 후(Phase 2) 실제 값 채움, Phase 1에서는 `null` 허용

**검증**
- 단위 테스트: 위 예시 골든 케이스 포함 경계값 5종
- DB 저장 없음: 시뮬레이션 후 `holdings.version`·`updated_at` 불변 확인, `AUDIT` 로그 없음
- P99 ≤ 5ms: Prometheus `allfolio_simulation_duration_seconds` 히스토그램 P99 확인

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
| `AuthIntegrationTest` | signup/login 흐름, 중복 이메일 등 |

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
| 감사 로그 마커 | `Holding` 생성/수정/삭제 시 `AUDIT` 마커 (PRD §6.8) |

**Phase 1 유예:** OpenTelemetry Bridge, Grafana Loki/Tempo 파이프라인 (Phase 4)

**AWS 이관 경로 (Phase 4 결정 시 참고):** PRD line 436 기준 인프라 벤더 미결정(AWS 유력). Phase 1은 벤더 중립 Prometheus registry 유지. AWS 확정 시 (a) ADOT Collector에 CloudWatch/X-Ray exporter 추가(앱 코드 변경 없음) 또는 (b) `micrometer-registry-cloudwatch` 의존성 추가로 이중 발행 가능. PRD §6.8·line 436은 벤더 결정 시점에 함께 개정.

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
- Lettuce 클라이언트의 `INCREX` 명령 지원 버전 확인 (PRD §6.2 Lua Script Fallback 검토)
- Redis 8.8 Testcontainers 이미지 가용 여부 확인

---

## 핵심 파일 목록 (신규 생성)

```
allfolio/
├── build.gradle.kts
├── settings.gradle.kts
├── docker-compose.yml
├── docs/
│   ├── PRD.md                          (기존)
│   ├── PHASE1_PLAN.md                  (본 문서)
│   └── CODE_REVIEW.md                  (Step 6에서 작성)
└── src/
    ├── main/
    │   ├── java/com/allfolio/
    │   │   ├── config/
    │   │   │   └── SecurityConfig.java
    │   │   ├── domain/
    │   │   │   ├── User.java
    │   │   │   ├── Asset.java
    │   │   │   ├── Holding.java
    │   │   │   ├── Transaction.java
    │   │   │   ├── repository/
    │   │   │   │   ├── UserRepository.java
    │   │   │   │   ├── AssetRepository.java
    │   │   │   │   └── HoldingRepository.java
    │   │   │   └── service/
    │   │   │       ├── AssetService.java
    │   │   │       └── SimulationService.java
    │   │   ├── web/
    │   │   │   ├── AuthController.java
    │   │   │   ├── AssetController.java
    │   │   │   ├── SimulationController.java
    │   │   │   └── GlobalExceptionHandler.java
    │   │   └── infra/
    │   │       └── security/
    │   │           ├── JwtIssuer.java
    │   │           └── JwtFilter.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__init.sql
    │           └── V2__indexes.sql
    └── test/
        └── java/com/allfolio/
            ├── BigDecimalPrecisionTest.java
            ├── SimulationServiceTest.java
            ├── AssetCrudIntegrationTest.java
            ├── OptimisticLockingTest.java
            └── AuthIntegrationTest.java
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
| Spring Boot 4.1.0 + Java 25 호환성 이슈 | 공식 문서 및 context7로 의존성 좌표 검증 후 착수 |
| BigDecimal 스케일 규칙 누락 | PRD §6.1 스케일 표를 `BigDecimalPrecisionTest`로 상수화, 모든 서비스 로직에 재사용 |
| Optimistic Lock 고빈도 충돌 | Phase 1은 유저 수 적으므로 단순 `@Version`으로 충분. 클라이언트 재시도 지침을 API 응답에 포함 |
| `uuidv7()` PG18 함수 미지원 | Testcontainers PG18 이미지에서 사전 검증. 미지원 시 Java UUID v7 라이브러리로 애플리케이션 레벨 생성 |
