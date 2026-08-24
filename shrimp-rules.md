# AllFolio AI Agent Development Guidelines

## Agent Routing

- **백엔드 애플리케이션 계층** (`web/`, `domain/`, `infra/`, `config/`, 테스트) → `senior-backend` 에이전트
- **프론트엔드 구조·동작** (`frontend/src/`의 컴포넌트 props·상태·핸들러, 라우팅, API 클라이언트, `lib/` 로직, 컴포넌트 테스트) → `senior-frontend` 에이전트
- **프론트엔드 시각 레이어** (`frontend/src/index.css`의 `@theme` 디자인 토큰, 각 컴포넌트의 className·마크업·ARIA/포커스 등 접근성 세부 구현, UI 카피, `docs/DESIGN.md`) → `ui-ux-designer` 에이전트
  - 경계: senior-frontend는 컴포넌트의 "구조와 동작"(무엇을 렌더링하고 어떻게 반응하는가)까지만 담당하고, 그 구조에 입히는 시각 표현은 ui-ux-designer 소관이다. 컴포넌트 파일 최상단에 `// 구조·동작: senior-frontend / className·마크업·문구: ui-ux-designer` 주석으로 경계를 명시한다
- **DB 계층** (`src/main/resources/db/migration/V*.sql`, JPA 엔티티 스키마 매핑) → `database` 에이전트
- **코드 리뷰** (읽기 전용 검증) → `code-reviewer` 에이전트
- Flyway 마이그레이션 추가 + 대응 JPA 엔티티 변경은 `database` 에이전트가 동시에 처리

---

## Project Phase Status

- **완료**: Task 001(스캐폴딩), Task 002(DB 스키마), Task 003(JWT 인증), Task 004(프론트 라우팅 골격), Task 005(엔티티·리포지토리·DTO 타입 정의), Task 006(API 계약 확정), Task 007(공통 컴포넌트·디자인 시스템), Task 012(자산 CRUD API)
- **우선순위**: Task 013(포트폴리오 홈 API) — Phase 2 백엔드 트랙 다음 단계
- 상세 Task 명세 → `docs/ROADMAP.md`

---

## Backend Layer Rules

### 패키지 배치 규칙

| 패키지 | 배치 대상 |
|---|---|
| `web/` | REST 컨트롤러, `dto/` (요청·응답 DTO) |
| `domain/` | JPA 엔티티, `service/` (비즈니스 로직), `repository/`, `exception/` |
| `infra/` | 외부 연동 (security, Redis, 외부 API 클라이언트) |
| `config/` | Spring 빈 설정, 보안 정책 |

- 컨트롤러에서 `domain.repository`를 직접 호출하지 말 것 — 반드시 `service`를 거칠 것
- DTO 클래스는 Java Record 사용
- 생성자 주입만 사용 (`@Autowired` 금지)

### 의존성 방향

```
web → domain.service → domain.repository
web → infra (금지: 컨트롤러에서 infra 직접 참조)
domain.service → infra (허용: JwtIssuer 등)
```

### Source Comment 규칙

- 코드 주석에 태스크 참조 시 **Task 번호** 사용 (`docs/ROADMAP.md Task 003`)
- Phase 번호 참조 금지 (`Phase 1 Step 3` 등) — Task 번호는 리넘버링 없는 고유값
- `V1__init.sql`의 기존 Phase 표기는 Flyway 체크섬 보호를 위해 그대로 유지

### Spring Boot 4.1 필수 주의사항

- Flyway 자동설정: `flyway-core`만으로는 마이그레이션이 조용히 건너뜀 → `org.springframework.boot:spring-boot-flyway` 모듈 필요
- MockMvc: `@SpringBootTest`에 자동 포함되지 않음 → `spring-boot-starter-webmvc-test` 테스트 의존성 추가 필요
- UserDetailsService 자동설정: `UserDetailsServiceAutoConfiguration` 반드시 exclude (`application.yml`에 이미 설정됨 — 삭제 금지)
- Testcontainers 버전: Spring Boot 4.1 BOM 관리 2.x 그대로 사용, 버전 고정 금지

---

## Database & Migration Rules

### Flyway 불변 규칙

- **이미 적용된 `V*.sql` 파일은 절대 수정 금지** — 체크섬 mismatch로 부팅 실패
- `V1__init.sql`의 주석도 수정 금지
- 신규 변경은 항상 새 마이그레이션 파일 추가 (`V3__*.sql`, `V4__*.sql` ...)
- 마이그레이션 위치: `src/main/resources/db/migration/`

### 스키마 규칙

- 모든 PK: `UUID DEFAULT uuidv7()` (PostgreSQL 18 네이티브)
- 모든 금융 컬럼: `NUMERIC(28,8)` — 예외 없음
- 낙관적 잠금 대상 테이블: `version INT NOT NULL DEFAULT 0` 컬럼 필수 (`holdings` 적용됨)
- FK는 `ON DELETE CASCADE` (현재 스키마 정책)

### JPA 엔티티 규칙

- 금융 필드: `@Column(columnDefinition = "NUMERIC(28,8)")` + `BigDecimal` 타입
- Optimistic Lock: `@Version` 어노테이션 (holdings 엔티티)
- `ddl-auto: validate` — 절대 `create`/`update`로 변경 금지

---

## Financial Precision Rules

- **`double`/`float` 절대 금지** — 금액, 수량, 비중, 비율 모두 `BigDecimal`
- `BigDecimal` 비교: `.compareTo()` 사용 (`.equals()`는 scale 차이로 실패)
- 스케일 및 반올림 규칙:

| 자산 유형 | 스케일 | RoundingMode |
|---|---|---|
| KRW 주식 | 0 | HALF_UP |
| USD 자산 | 4 | HALF_UP |
| 코인 | 8 | HALF_UP |
| 비중(%) | 2 | HALF_UP |

- JSON 직렬화: 금액·수량은 **문자열**로 (`"60000"`, `"0.5"`) — 부동소수 손실 방지
- double 사용 여부 검사: `grep -r "double " src/main/java --include="*.java" | grep -v "Double\|//"`

---

## Security Rules

- `ALLFOLIO_JWT_SECRET` 환경변수 미설정 시 부팅 실패 (의도된 동작) — 기본값 채워 넣기 금지
- JWT Access Token TTL: 15분 (Refresh Token은 Task 019 이전 미구현)
- user enumeration 방지: 이메일 없음 / 비밀번호 불일치 → 동일 에러 메시지 `INVALID_CREDENTIALS`
- 타 유저 자산 접근 시: **403이 아닌 404** 반환 (ID 유출 방지)

---

## API Contract Rules

### 공통 규칙

- 모든 API path prefix: `/v1/`
- 인증 필요 엔드포인트: `Authorization: Bearer <token>` 헤더
- 에러 응답 포맷 (항상 3필드 고정):
  ```json
  { "code": "ERROR_CODE", "message": "설명", "timestamp": "2026-08-05T10:00:00Z" }
  ```

### 구현된 엔드포인트 (Task 003)

| Method | Path | Status |
|---|---|---|
| `POST` | `/v1/auth/signup` | 201 / 409 |
| `POST` | `/v1/auth/login` | 200 / 401 |

### 구현된 엔드포인트 (Task 012)

| Method | Path | 비고 |
|---|---|---|
| `POST` | `/v1/assets` | 201, CASH는 `avgPrice`를 요청값과 무관하게 서버가 `1` 고정 삽입 |
| `GET` | `/v1/assets` | 커서 페이지네이션 (`limit` 기본 20/max 100, `cursor`) |
| `GET` | `/v1/assets/{id}` | 타 유저 접근 시 404 |
| `PUT` | `/v1/assets/{id}/holdings` | 요청에 `version` 필수, 낙관적 잠금은 서비스에서 수동 비교, 충돌 시 `HOLDING_CONFLICT` 409 |
| `DELETE` | `/v1/assets/{id}` | 204 |

### 계약 확정, 구현 예정 (Task 006에서 DTO·에러코드 확정 / Task 013·015에서 컨트롤러 구현)

| Method | Path | 비고 |
|---|---|---|
| `GET` | `/v1/portfolio` | Phase 2에서 `evaluationKrw`·`unrealizedPnl`·`weight`는 `null` |
| `POST` | `/v1/simulate/avg-price` | DB 저장 없음, P99 ≤ 5ms |

### 에러 코드 목록

- **구현됨 (Task 003)**: `EMAIL_ALREADY_EXISTS`(409), `INVALID_CREDENTIALS`(401), `UNAUTHORIZED`(401), `VALIDATION_ERROR`(400), `NOT_FOUND`(404), `METHOD_NOT_ALLOWED`(405), `UNSUPPORTED_MEDIA_TYPE`(415), `NOT_ACCEPTABLE`(406), `CLIENT_ERROR`(4xx), `INTERNAL_ERROR`(500)
- **구현됨 (Task 006, Task 012에서 실제 사용 시작)**: `ASSET_NOT_FOUND`(404, 타 유저 소유 자산도 동일 코드), `HOLDING_CONFLICT`(409, 낙관적 잠금 충돌), `CONFLICT`(409, 매칭되는 도메인 코드가 없는 데이터 무결성 제약 위반 폴백)

---

## Frontend Rules

### 파일 구조

```
frontend/src/
  pages/          라우트별 페이지 컴포넌트 (LoginPage, SignupPage, PortfolioPage, AssetNewPage, AssetDetailPage, DevUiPage — Task 018에서 제거 예정)
  layouts/        AppLayout (헤더 O, 인증 라우트용), AuthLayout (헤더 X)
  auth/           authContext.ts, AuthProvider.tsx, useAuth.ts, tokenStorage.ts, RequireAuth.tsx
  api/            types.ts(계약 단일 출처), fixtures.ts(더미 응답 — Task 018에서 제거 예정)
  components/     공통 컴포넌트(Button/Field/TextField/Alert/Card/ConfirmDialog/SegmentToggle) + 각 `*.test.tsx`. 신규 공통 컴포넌트 중 직접 DOM 루트를 렌더링하는 컴포넌트는 이 디렉터리에 배치하고 `testId` prop을 반드시 노출(렌더-프롭 래퍼처럼 자체 DOM 루트가 없는 경우는 예외 — 예: `Field`는 label/error/hint만 렌더링하고 실제 입력 요소는 `children` 콜백이 만들어 소비자인 `TextField`가 자기 `testId`를 직접 부착한다)
  lib/            금융 정밀도·검증·문구 유틸(big.ts/money.ts/validation.ts/messages.ts/simulate.ts) + 각 `*.test.ts`. UI 관심사 없는 순수 함수만 배치
  test/           Vitest 전역 설정(setup.ts) — 환경은 `happy-dom` (jsdom은 `<dialog>.showModal()` 미구현으로 사용 금지, jsdom#3294)
  router.tsx      라우팅 정의 (react-router v8)
  main.tsx        진입점
```

### 타입·더미 데이터 규칙 (Task 006)

- API 요청·응답 타입은 `frontend/src/api/types.ts`가 단일 출처 — 필드 추가/변경 시 `docs/ROADMAP.md` 「API 규격」도 함께 갱신
- `tsconfig.app.json`의 `erasableSyntaxOnly` 때문에 `enum` 키워드 금지 — `as const` 배열 + 인덱스 유니온 패턴 사용 (`types.ts`의 `ASSET_TYPES`, `ERROR_CODES` 참고)
- Task 007~011은 실제 fetch 대신 `frontend/src/api/fixtures.ts`의 더미 응답을 사용

### 라우팅 규칙

- 라우트 변경 시 반드시 `frontend/src/router.tsx`와 해당 `pages/` 파일을 동시에 수정
- 인증 필요 라우트는 `RequireAuth` 래퍼 안에 배치
- 비인증 라우트(`/login`, `/signup`)는 `AuthLayout` 하위에 배치

### 인증 상태 관리

- 토큰 접근: `useAuth()` 훅 사용 (`authContext.ts`의 `AuthContext` 직접 접근 금지)
- 토큰 저장소: `tokenStorage.ts` (localStorage key: `allfolio_token`) — 다른 key 사용 금지
- 인증 상태 변경 시 수정 대상: `authContext.ts` → `AuthProvider.tsx` → `useAuth.ts` 순으로 연동 확인

### 금액 렌더링

- API에서 금액은 문자열로 수신 (`"60000"`) — `parseFloat`/`Number()` 변환 금지
- 화면 표시 시 `frontend/src/lib/money.ts`의 포맷터 함수 사용 (`formatAmount`/`formatQuantity`/`formatWeight`/`formatSignedAmount` 등, `big.js` 기반 HALF_UP 반올림)
- `toFixed(` 호출은 `frontend/src/lib/big.ts` 1곳(표시 포맷터 내부)만 허용 — 그 외 위치에서 `toFixed(` 호출 금지(네이티브 `toFixed`는 HALF_EVEN 계열이라 「금융 정밀도 규칙」의 HALF_UP과 다른 결과를 낼 수 있음). 검증: `cd frontend && grep -rn "toFixed(" src --include="*.ts" --include="*.tsx" | grep -v '^src/lib/big.ts:'` → 0건이어야 함(테스트 파일에서 네이티브 `toFixed`의 오차를 예시로 보여주는 경우는 예외)
- `TextField`에 `type="number"` 사용 금지 — 네이티브 number input의 `valueAsNumber`가 `double`(IEEE 754)을 경유해 `NUMERIC(28,8)` 정밀도를 깨뜨린다. 숫자 입력은 `type="text"` + `inputMode="decimal"` + `lib/validation.ts`의 문자열 기반 검증으로 처리

### `data-testid` 네이밍 규칙

- 형식: kebab-case `'<화면>-<요소>[-<수식어>]'` (예: `login-email-input`, `asset-new-submit`, `confirm-delete-cancel`)
- 공통 컴포넌트(Button/TextField/ConfirmDialog 등)는 `testId` prop을 그대로 최상위 엘리먼트에 부착만 한다 — 컴포넌트가 내부적으로 접미사를 붙이는 등 자체 생성 로직을 갖지 않는다(단, `ConfirmDialog`처럼 여러 자식 버튼이 있는 복합 컴포넌트는 전달받은 `testId`에 `-cancel`/`-confirm` 등 고정 접미사를 붙여 하위 요소에 배분하는 것은 허용)
- 실제 `data-testid` 값은 각 페이지(`pages/`)에서 화면·요소 이름을 조합해 호출부에서 결정한다

### Vite 개발 프록시

- `/v1/*` → `localhost:8080` 자동 프록시 (개발 중 CORS 설정 불필요)
- Capacitor 빌드 후엔 `capacitor://` 출처가 달라 CORS 필요 (Task 027에서 처리)

---

## Multi-File Coordination

신규 Task 구현 시 아래 파일은 함께 수정해야 한다:

| 변경 사항 | 함께 수정할 파일 |
|---|---|
| 신규 API 엔드포인트 추가 | `web/XxxController.java` + `web/dto/` + `domain/service/XxxService.java` + `docs/ROADMAP.md` |
| 신규 Flyway 마이그레이션 | `V*.sql` + 대응 JPA 엔티티 (database 에이전트 동시 처리) |
| 신규 에러 코드 추가 | `GlobalExceptionHandler.java` + `web/dto/ErrorResponse.java` + `docs/ROADMAP.md` 에러 코드 목록 |
| 신규 라우트 추가 | `frontend/src/router.tsx` + `frontend/src/pages/XxxPage.tsx` |
| Task 완료 처리 | `docs/ROADMAP.md` 해당 Task ✅ 표시 |

---

## Simulator Rules (Task 015)

- `POST /v1/simulate/avg-price`: **DB INSERT/UPDATE 없음**, Holding 단건 조회 후 In-Memory 계산만
- 가중평균 계산: `(기존평단 × 기존수량 + 추가가격 × 추가수량) / (기존수량 + 추가수량)`
- 응답의 `currentWeight`/`expectedWeight`: Phase 1~2에서 `null` (외부 시세 없음)
- P99 ≤ 5ms KPI는 JVM 워밍업 후 측정 (`allfolio_simulation_duration_seconds` 히스토그램)

---

## Prohibited Actions

- `double`/`float` 금융 계산에 사용
- 이미 적용된 `V*.sql` 파일 내용 수정 (주석 포함)
- `ALLFOLIO_JWT_SECRET`에 기본값 하드코딩
- `ddl-auto`를 `validate` 이외 값으로 변경
- `spring.threads.virtual.enabled: false`로 변경
- Testcontainers 버전 수동 고정 (`spring-boot-starter-test`의 BOM 관리를 따를 것)
- 컨트롤러에서 Repository 직접 주입
- Phase 번호를 소스 주석에 사용 (Task 번호만 허용)
- 타 유저 자산 접근 시 403 반환 (반드시 404)
- `AuthContext`를 `useAuth()` 훅 없이 직접 접근
- `localStorage.getItem('allfolio_token')` 직접 호출 (`tokenStorage.ts` 통해 접근)
- 시뮬레이터 API에서 DB Write 발생
- `BigDecimal.equals()`로 금융 값 비교 (`.compareTo()` 사용)
- `frontend/src/lib/big.ts` 외 위치에서 `toFixed(` 호출
- 프론트 `TextField`에 `type="number"` 사용 (`valueAsNumber`가 `double` 경유로 정밀도 손실)
