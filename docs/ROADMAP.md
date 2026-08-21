# AllFolio 개발 로드맵

**최종 수정:** 2026-08-21
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

### Phase 1: 애플리케이션 골격 구축 ✅ 완료 (2026-08-17)

양쪽 트랙(프론트/백엔드)이 서로를 기다리지 않고 출발할 수 있는 상태를 만드는 단계. Task 001~006 전체 완료로 Phase 1을 종료하고, 이제 Phase 2(2-A 프론트 UI, 2-B 백엔드 도메인) 병렬 트랙에 착수한다.

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

- **Task 004: 프론트엔드 프로젝트 셋업 및 라우팅 골격** ✅ — 완료
  - ✅ `frontend/` React + Vite + TypeScript 초기화 (react-router v8, Tailwind CSS v4)
  - ✅ 5개 라우트(`/login`, `/signup`, `/portfolio`, `/assets/new`, `/assets/:id`)의 빈 페이지 파일
  - ✅ `AppLayout`(헤더 O, 인증 라우트용) / `AuthLayout`(헤더 X, 로그인·회원가입용) 2계층 레이아웃 분리
  - ✅ `AuthProvider` + `AuthContext` + `useAuth` 훅 파일 분리 구조 (`authContext.ts` / `AuthProvider.tsx` / `useAuth.ts` / `tokenStorage.ts`)
  - ✅ `RequireAuth` 인증 가드 (비로그인 접근 시 `state.from`에 이전 경로 캡처 후 `/login` 리디렉션), 토큰 localStorage 저장
  - ✅ Vite dev server 프록시 설정 (`/v1/*` → `localhost:8080`)
  - ✅ 루트 `.gitignore`에 `frontend/node_modules/`, `frontend/dist/`, `frontend/.vite/` 추가

- **Task 005: 백엔드 도메인 엔티티·리포지토리·DTO 타입 정의** ✅ — 완료
  - ✅ `Asset`/`Holding`(`@Version`)/`Transaction` 엔티티
  - ✅ `AssetRepository`(소유권 격리 쿼리 `findByIdAndUser_Id` 포함)/`HoldingRepository`
  - ✅ 요청·응답 DTO 5종 (`CreateAssetRequest`/`UpdateHoldingRequest`/`AssetResponse`/`SimulateAvgPriceRequest`/`SimulateAvgPriceResponse`)
  - ✅ 금융 필드 전부 `BigDecimal` + `NUMERIC(28,8)` 매핑, 응답 금액 String 직렬화, `double`/`float` 0건 (code-reviewer 검증)

- **Task 006: API 계약 확정 및 미결정 사항 4건 해소** ✅ — 완료
  - ✅ 신규 엔드포인트 7종 규격 확정 (자산 5종·포트폴리오·시뮬레이터, 아래 「API 규격」 절 — 인증 2종은 Task 003에서 이미 구현)
  - ✅ `ASSET_NOT_FOUND`·`HOLDING_CONFLICT` 에러 코드 추가
  - ✅ 프론트용 TypeScript 타입(`frontend/src/api/types.ts`) 및 더미 응답 픽스처(`frontend/src/api/fixtures.ts`) 작성
  - ✅ 아래 「확정된 설계 결정」 4건 확정 (구 「착수 전 결정 사항」)
  - ✅ `PUT /v1/assets/{id}/holdings` 요청·응답에 낙관적 잠금 `version` 필드 추가 — 구 계약은 `HOLDING_CONFLICT` 409를 약속했지만 요청 본문에 `version`이 없어 실제로는 발생할 수 없는 결함이었음
  - ✅ `ApiContractSerializationTest`(`@JsonTest`)로 직렬화 계약(enum 문자열화, 금액 문자열화, `null` 필드 보존, 시뮬레이터 골든 케이스) 고정
  - ✅ 이 Task가 Phase 2 병렬 트랙의 출발점이다. 컨트롤러 구현(Task 012·013·015)은 이 Task 범위 밖이며, 여기서는 DTO·에러 코드·직렬화 형태만 확정한다.
  - **Task 005 리뷰 후속 조치 (반영 완료)**
    - ✅ `CreateAssetRequest.currency` 검증을 `@Pattern("^[A-Z]{3}$")`로 강화 (`web/dto/CreateAssetRequest.java`)
    - ✅ 응답 DTO enum 직렬화 정책 확정 — `AssetResponse.assetType`은 `"STOCK"` 문자열로 직렬화(Jackson 기본 동작)되며 `ApiContractSerializationTest`로 고정. TypeScript `AssetType`도 동일한 문자열 유니온으로 반영

### Phase 2: UI/UX 완성 + 백엔드 도메인 구현 (병렬 2트랙) 🔵 진행 중

두 트랙 모두 Task 006(API 계약)을 선행 조건으로 한다.

**2-A. 프론트엔드 UI (더미 데이터로 완성)**

- **Task 007: 공통 컴포넌트 및 디자인 시스템 구축** ✅ — 완료
  - ✅ 금융 정밀도 유틸(`frontend/src/lib/big.ts`/`money.ts`/`validation.ts`/`simulate.ts`) — `big.js`(십진 연산 라이브러리) 채택. 「금융 정밀도 규칙」의 스케일·HALF_UP을 상수로 매핑, `toFixed()`는 `money.ts`의 표시 포맷터 내부 1곳(`big.ts`)에서만 호출
  - ✅ 공통 컴포넌트 6종(Button/Field/TextField/Alert/Card/ConfirmDialog) — 구조·props·상태·핸들러·`data-testid`는 senior-frontend, 시각 표현(className·마크업·ARIA·포커스 링, `src/index.css` `@theme` 토큰)은 ui-ux-designer(`docs/DESIGN.md` 참고)
  - ✅ 문구 매핑(`frontend/src/lib/messages.ts`) — 검증/에러 코드 → 한국어 문구. 키 구조는 senior-frontend가 코드 타입에서 고정, 문구 자체는 ui-ux-designer가 최종본으로 확정
  - ✅ 테스트 인프라 — Vitest + `@testing-library/react`. DOM 환경은 `jsdom` 대신 `happy-dom` 채택: `ConfirmDialog`가 네이티브 `<dialog>`의 `showModal()`을 쓰는데 jsdom이 이를 구현하지 않아(jsdom#3294) 테스트가 깨짐. `happy-dom`은 `showModal()`을 지원해 이 문제가 없다
  - ✅ 토스트 컴포넌트는 만들지 않는다 — 별도 Toast 대신 "라우터 state로 결과 전달(`navigate(path, { state: { flash } })`) + 이동 대상 페이지에서 기존 `Alert` 컴포넌트로 렌더링" 패턴으로 대체. 근거: 자동 소멸 타이머·큐잉·스택 쌓임 등 Toast 특유의 상태 관리가 이 앱의 화면 흐름(등록/수정/삭제 후 항상 다른 화면으로 이동)에는 불필요한 복잡도이고, `Alert`가 이미 톤·아이콘·`data-testid`를 갖추고 있어 재사용 가능. 실제 적용은 Task 008~011에서 각 폼 제출 흐름에 붙인다
  - ✅ `/dev/ui` 컴포넌트 쇼케이스 라우트(`frontend/src/pages/DevUiPage.tsx`) — `import.meta.env.DEV`로만 라우터에 등록해 프로덕션 빌드에서 제외. **Task 018(실 API 연동 착수) 시점에 라우트·페이지 파일 함께 제거 예정**
  - 반응형 기준: ui-ux-designer 소관, `docs/DESIGN.md` 참고
  - 정밀도 grep 검증 규칙: `grep -rn "parseFloat\|Number(\|toFixed(" frontend/src`에서 `src/lib/big.ts` 1곳(표시 포맷터 내부)을 제외하면 0건이어야 한다(테스트 파일에서 네이티브 `toFixed`의 오차를 예시로 대조하는 경우는 예외 — 예: `money.test.ts`의 `(1.005).toFixed(2)` 대조). `TextField`는 `type="number"`/`valueAsNumber`를 쓰지 않는다(`valueAsNumber`가 `double`을 경유해 정밀도 규칙을 깨기 때문)

- **Task 008: 인증 화면 구현 (F010)** ✅ — 완료
  - ✅ `frontend/src/api/authApi.ts` — 더미 데이터가 아닌 실 백엔드(`POST /v1/auth/signup`·`POST /v1/auth/login`, Task 003에서 이미 완성)를 fetch로 직접 호출. 다른 2-A 화면과 달리 이 화면만 처음부터 실 API를 쓴다 — 인증 백엔드에는 외부 시세 같은 미해결 의존성이 없어 더미로 흉내 낼 이유가 없기 때문. `ApiError`는 `code`만 담고, 문구는 화면이 `messageForErrorCode()`로 렌더링 시점에 조회
  - ✅ `LoginPage.tsx`/`SignupPage.tsx` — 이메일·비밀번호 폼(`lib/validation.ts`로 클라이언트 검증), 성공 시 `AuthProvider.login()` 후 이동(로그인은 `location.state.from` 우선, 회원가입은 자동 로그인 후 `/portfolio`), 실패 시 `Alert`로 에러 문구 표시. `<form noValidate>`로 브라우저 네이티브 검증 팝업이 커스텀 에러 UI를 가리는 문제 방지
  - ✅ 로그인 비밀번호는 `validatePassword()`(8자 강제)를 쓰지 않고 빈 값만 검사한다 — 백엔드 `LoginRequest`가 의도적으로 길이 제약을 두지 않기 때문(정책 변경 전 가입자 로그인 차단 방지, 400/401 응답 차이로 비밀번호 정책이 유추되는 것도 방지). 회원가입은 `validatePassword()`를 그대로 사용(8자 이상 + UTF-8 72바이트 이하)
  - ✅ `LoginPage.test.tsx`/`SignupPage.test.tsx` — Vitest + Testing Library, 6개 케이스(검증 실패·성공·서버 에러·기본 리다이렉트), `fetch` 모킹
  - ✅ 시각·카피 다듬기(ui-ux-designer) — `docs/DESIGN.md` §6-1 "인증 화면 골격" 신설(워드마크 → h1 → 한 줄 설명(회원가입만) → 폼 → 실선 구분선 → 반대편 화면 링크)
  - ✅ code-reviewer 독립 검증 2회(구현 직후 Minor 4건 발견·수정, 재검증에서 뮤테이션 테스트로 수정 사항 실측 확인) — Blocker 0건

- **Task 009: 포트폴리오 홈 화면 구현 (F005)** ✅ — 완료 (화면 명칭이 구현 중 "포트폴리오"에서 **"총 자산"**으로 바뀜)
  - ✅ `frontend/src/pages/PortfolioPage.tsx` — `frontend/src/api/fixtures.ts`의 `portfolioFixture`(더미 데이터)를 정적 렌더링. 백엔드 포트폴리오 API(Task 013)가 아직 없어 2-A 원칙대로 더미로 완성, 실 연동은 Task 018
  - ✅ 사용자 피드백에 따라 1차 구현 후 5차례 재개편(사용자가 다른 증권 앱 스크린샷을 보여주며 방향 논의 → 라이트 테마 유지하되 패턴만 채택하기로 합의, 이후 화면 콘셉트를 "총 자산"으로 전면 재정의) — 최종 화면 구성:
    - `<h1>` "총 자산", "자산 등록" 버튼은 특정 그룹에 속하지 않는 페이지 레벨 액션(h1과 같은 줄)
    - 합계 카드는 통화별(KRW/USD) 취득원가 병기 대신 `totalEvaluationKrw` 단일 KRW 히어로 숫자 + 평가손익. 값이 `null`(Phase 2)이면 "시세 연동 전" 상태 표식으로 축소해 빈 값과 렌더 오류를 구분
    - 자산 목록을 **투자 자산**(STOCK/COIN, `portfolio-investment-list`)과 **현금 자산**(CASH, `portfolio-cash-list`) 두 그룹으로 분리(0건 그룹은 렌더하지 않음)
    - 목록 행을 6필드(수량·평단가·취득원가·평가금액·평가손익·비중)에서 **3필드(평가금액·손익·수량)**로 축소 — 평단가·취득원가·비중은 행 클릭 시 이동하는 자산 상세 화면(Task 011)으로 이관
    - 종목별 원형 이니셜 아바타 도입(로고 이미지 없음, 무채 배지 — 다크 테마·도넛 차트·세그먼트 토글·행별 일간수익은 명시적으로 채택하지 않음. 근거는 `docs/DESIGN.md` §8 폐기안)
  - ✅ `frontend/src/lib/money.ts` 버그 수정 — `formatQuantity`에 천 단위 그룹핑 추가(`groupThousands` 공용 헬퍼로 `formatAmount`와 공유), `evaluationKrw`·`unrealizedPnl`을 원자산 통화/유형이 아닌 **항상 KRW 스케일**로 표시하도록 통일(COIN 자산에서 소수 8자리로 잘못 찍히던 버그 2건 — 하나는 구현 중 자체 발견, 하나는 code-reviewer 재검증 중 발견). 위 「API 규격」 절에 `unrealizedPnl`도 KRW 환산액이라는 계약을 명시해둠
  - ✅ `frontend/src/pages/PortfolioPage.test.tsx` — 9개 케이스(항목 렌더·그룹 분리·null 표시·요약·네비게이션 2종·제목 고정·3필드 고정·`vi.doMock`으로 `portfolioFixture`를 대체해 KRW 스케일 회귀를 실측하는 테스트)
  - ✅ 시각·카피 다듬기(ui-ux-designer, 반복 4회) — `docs/DESIGN.md` §6-2 "목록 화면 골격" 신설·재개정(현재 버전 1.4.0). 이 앱 최초의 데이터 목록 화면이라 행 구분·컬럼 헤더 유무·반응형 접힘·히어로 숫자의 빈 값 처리 등을 여기서 새로 정했다
  - ✅ code-reviewer 독립 검증 2회 — 1차 Minor 4건 발견·수정, 2차(5단계 개편 이력 전체 대조 + 뮤테이션 테스트)에서 Major 1건(`unrealizedPnl` 통화 기준 불일치)·Minor 4건 발견, 전부 수정 완료. Blocker 0건
  - ⚠️ 남은 갭: `totalCostByCurrency`(통화별 취득원가 Map, API 계약에는 여전히 존재·`PortfolioResponse` 유지)가 이 화면에서는 더 이상 표시되지 않는다 — 필드 자체를 계약에서 뺄지는 Task 013 착수 시 재검토

- **Task 010: 자산 등록 화면 구현 (F001)**
  - 유형(STOCK/COIN/CASH)·티커·통화·수량·평단가 입력
  - CASH 선택 시 평단가 입력란을 숨긴다 — 서버가 `avgPrice=1`을 고정 삽입하므로 프론트는 요청 본문에서 `avgPrice`를 생략(`null`)하면 된다 (Task 006 결정 #1)

- **Task 011: 자산 상세 화면 구현 (F002·F003·F004·F006·F007)**
  - 상세 정보, 차트 영역(정적 더미), 물타기 시뮬레이터 폼, 수정 폼, 삭제 확인 팝업
  - 삭제 확인 팝업에 "보유 정보와 거래 이력이 함께 삭제되며 복구할 수 없습니다" 경고 문구 포함 (Task 006 결정 #4)
  - 수정 폼은 상세 조회 응답의 `version` 값을 화면에 노출하지 않고 폼 상태로 들고 있다가 `PUT` 요청에 그대로 실어 보낸다. 409 `HOLDING_CONFLICT` 수신 시 "다른 곳에서 이미 수정되었습니다. 새로고침 후 다시 시도하세요" 표시
  - **착수 전 확인 필요 (Task 009 재개편 후속)**: 평단가·취득원가·비중(%)이 Task 009에서 총 자산 목록 행 대신 이 화면에서 보여주기로 결정됐다. 그런데 `GET /v1/assets/{id}`(`AssetResponse`, 위 「API 규격」 절)에는 `quantity`/`avgPrice`만 있고 `cost`·`evaluationKrw`·`unrealizedPnl`·`weight`는 없다 — 그 필드들은 `PortfolioItem`에만 있는 **포트폴리오 전체 맥락값**이다. 취득원가(`cost`)는 `avgPrice × quantity`로 프론트에서 바로 계산할 수 있어 문제없지만, **비중(weight)은 전체 포트폴리오 평가금액이 분모라 단일 자산 조회만으로는 계산할 수 없다.** 착수 전에 (a) 상세 진입 시 `GET /v1/portfolio`를 다시 불러 해당 항목을 찾을지, (b) 목록에서 클릭한 `PortfolioItem`을 라우터 state로 넘길지, (c) `AssetResponse`에 `weight` 필드를 추가할지 중 하나를 정해야 한다

**2-B. 백엔드 도메인 로직**

- **Task 012: 자산 CRUD API 구현 (F001~F004)**
  - 5개 엔드포인트, 유저 소유권 격리(타 유저 자산은 403이 아닌 **404** — ID 유출 방지)
  - 자산 생성 시 Holding + `transactions`(BUY 1건) 동시 생성 (단일 트랜잭션, Task 006 결정 #2). 보유 수정(F003)은 `transactions`를 기록하지 않는다
  - 종목 중복 등록은 허용한다 — `(user_id, ticker)` UNIQUE 제약을 추가하지 않는다 (Task 006 결정 #3)
  - CASH 자산 생성 시 요청의 `avgPrice`가 `null`이어도 서버가 `avgPrice=1`을 강제 삽입 (Task 006 결정 #1)
  - **Task 005 리뷰 후속 조치 (착수 전 반영)**
    - `AssetRepository.findAllByUser_Id`를 커서 페이지네이션 시그니처로 대체 — Task 006 확정 기준 `limit` 기본 20/max 100, `cursor` 파라미터에 맞춰 재정의 (`domain/repository/AssetRepository.java:12`)
    - `AssetResponse.from(Asset, Holding)` 팩토리의 엔티티 매핑 로직을 `AssetService`(또는 별도 매퍼)로 이동 — `spring.jpa.open-in-view: false` 환경에서 트랜잭션 밖 Lazy 접근 위험 제거. DTO는 순수 값 파라미터만 받도록 축소 (`web/dto/AssetResponse.java:21-32`)
  - **Task 006 리뷰 후속 조치 (착수 전 반영)**
    - `ApiContractSerializationTest`에 `AvgPriceRequiredUnlessCash` 검증 테스트, `SimulateAvgPriceResponse.currentWeight/expectedWeight`의 null 키 보존 테스트, `AssetListResponse`/`PortfolioResponse` 직렬화 테스트, 요청 방향(`"60000"` 문자열 → `BigDecimal`) 역직렬화 테스트 보강 — Task 010 폼이 문자열로 POST하므로 역직렬화 검증 공백이 가장 큰 잔여 리스크
    - `GlobalExceptionHandler.handleDataIntegrityViolation`의 제약 판별을 원인 메시지 문자열 매칭(`contains("uk_users_email")`)에서 `ConstraintViolationException.getConstraintName()` 기반으로 전환 — 이 Task에서 `holdings`의 `uk_holdings_asset_id` 등 제약이 늘어나면 메시지 포맷 의존이 취약해진다

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
  - 응답에 `expectedQuantity` 필드(scale 8) 포함 필수
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
  - Task 007에서 추가한 `/dev/ui` 컴포넌트 쇼케이스 라우트·`DevUiPage.tsx` 제거 (더미 데이터 단계 전용 도구였으므로)

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
  - Task 006 결정 #2에 따라 자산 등록 시 `BUY` 1건만 자동 기록되므로(보유 수정은 미기록), 이 Task는 (a) 누적된 이력 조회 API와 (b) 사용자가 실제 매매·배당을 직접 입력하는 API를 함께 구현한다

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
  - 웹폰트(IBM Plex) self-host 전환 검토 — `frontend/index.html`이 Google Fonts CDN에서 로드해, 오프라인/제한된 네트워크 환경에서 로드 실패 시 `--font-sans` 폴백으로 떨어질 수 있음

- **Task 028: 부하 검증 및 성능 튜닝**
  - Virtual Thread 1,000+ 동시 SSE 커넥션, k6 벤치마크

- **Task 029: 배포 파이프라인 및 운영 관측 체계**
  - CI/CD, 프론트 배포 방식 확정(JAR 통합 vs 분리 호스팅)
  - OpenTelemetry Bridge, Grafana Loki/Tempo
  - 인프라 벤더 미결정 유지 — 벤더 확정 시 (a) ADOT Collector에 exporter 추가(앱 코드 변경 없음) 또는 (b) `micrometer-registry-cloudwatch` 의존성 추가로 이중 발행 가능

---

## API 규격

이 절이 확정한 것은 요청·응답 DTO의 최종 형태와 에러 코드다. **엔드포인트 자체(컨트롤러)의 실제 구현은 Task 012·013·015 소관**이며, Task 006 시점에는 아직 존재하지 않는다. 직렬화 형태(enum 문자열화, 금액 문자열화, `null` 필드 보존)는 `ApiContractSerializationTest`(`@JsonTest`)로 고정돼 있다.

| Method | Path | Status | 비고 |
|---|---|---|---|
| `POST` | `/v1/auth/signup` | 201 / 409 | 가입 성공 시 JWT 자동 발급, 이메일 중복 시 `EMAIL_ALREADY_EXISTS` (F010, Task 003) |
| `POST` | `/v1/auth/login` | 200 / 401 | 실패 시 `INVALID_CREDENTIALS` (F010, Task 003) |
| `POST` | `/v1/assets` | 201 | 자산+Holding+거래이력(BUY) 단일 트랜잭션 생성 |
| `GET` | `/v1/assets` | 200 | 커서 페이지네이션 (`limit` 기본 20/max 100, `cursor`), 최신 등록 순 |
| `GET` | `/v1/assets/{id}` | 200 / 404 | 타 유저 접근 시 404 (ID 유출 방지) |
| `PUT` | `/v1/assets/{id}/holdings` | 200 / 409 | 낙관적 잠금 `version` 필수, 충돌 시 `HOLDING_CONFLICT` |
| `DELETE` | `/v1/assets/{id}` | 204 / 404 | `ON DELETE CASCADE`로 holdings·transactions 함께 삭제 |
| `GET` | `/v1/portfolio` | 200 | Task 013 범위(F005a)는 취득원가만, `evaluationKrw`·`unrealizedPnl`·`weight`는 `null` |
| `POST` | `/v1/simulate/avg-price` | 200 | DB 저장 없음 |

**Bean Validation 규칙**
- `ticker`: 1~20자, 공백 불가
- `currency`: 정확히 3자 대문자 (`^[A-Z]{3}$`)
- `quantity`: `≥ 0`, `NUMERIC(28,8)` 범위
- `avgPrice`(`POST /v1/assets`): STOCK/COIN은 `> 0` 필수, CASH는 `null` 허용(서버가 `1`로 강제 삽입) — `AvgPriceRequiredUnlessCash` 클래스 레벨 제약으로 검증 (Task 006 결정 #1)
- `avgPrice`(`PUT /v1/assets/{id}/holdings`): 대상 자산이 CASH면 요청값을 무시하고 `1`을 유지, 그 외 자산에서 `null`이면 `POST`와 동일하게 400 `VALIDATION_ERROR`(대상 자산의 assetType은 서버가 경로 `{id}`로 조회해 판단하므로 `CreateAssetRequest`처럼 클래스 레벨 제약으로는 표현할 수 없다 — Task 012 서비스 로직에서 검증)
- `version`(`PUT /v1/assets/{id}/holdings`): 필수, 상세 조회 응답의 값을 그대로 반환

**금액·수량은 JSON에서 문자열로 직렬화** (부동소수 손실 방지). `assetType` 등 enum은 대문자 문자열로 직렬화(`"STOCK"`).

### `POST /v1/assets` 요청 예시

```json
// STOCK
{ "ticker": "005930", "name": "삼성전자", "assetType": "STOCK",
  "currency": "KRW", "quantity": "10", "avgPrice": "60000" }

// CASH — avgPrice 생략(null), 서버가 1로 채움
{ "ticker": "KRW", "name": "원화 예수금", "assetType": "CASH",
  "currency": "KRW", "quantity": "1500000", "avgPrice": null }
```

응답 본문은 두 경우 모두 `AssetResponse`(아래 `GET /v1/assets/{id}` 응답과 동일 형태).

### `GET /v1/assets/{id}` 응답 예시

```json
{
  "id": "0198f2a1-...", "ticker": "005930", "name": "삼성전자",
  "assetType": "STOCK", "currency": "KRW",
  "quantity": "10", "avgPrice": "60000",
  "version": 0, "updatedAt": "2026-08-17T09:00:00Z"
}
```

### `GET /v1/assets` 응답 예시

```json
{ "items": [ /* 위 AssetResponse[] */ ], "nextCursor": "0198f2a1-..." }
```

`nextCursor`는 불투명 문자열로 취급한다(클라이언트가 값을 해석하지 않는다). 마지막 페이지면 `null`.

### `PUT /v1/assets/{id}/holdings` 요청 예시

```json
{ "quantity": "15", "avgPrice": "58000", "version": 0 }
```

응답 본문은 갱신된 `AssetResponse`(`version`이 1 증가한 값 포함) — 클라이언트가 다음 수정에 쓸 새 `version`을 이 응답에서 얻는다. 대상 자산이 CASH면 요청의 `avgPrice`는 무시되고 `1`이 유지된다.

### `GET /v1/portfolio` 응답 예시

```json
{
  "items": [
    { "assetId": "0198...", "ticker": "005930", "name": "삼성전자",
      "assetType": "STOCK", "currency": "KRW",
      "quantity": "10", "avgPrice": "60000", "cost": "600000",
      "evaluationKrw": null, "unrealizedPnl": null, "weight": null }
  ],
  "totalCostByCurrency": { "KRW": "600000" },
  "totalEvaluationKrw": null,
  "totalUnrealizedPnl": null
}
```

`totalCostByCurrency`는 `items`에 담긴 모든 자산의 취득원가를 통화별로 합산한 값이다(위 예시는 자산 1건뿐이라 KRW 값이 그 자산의 `cost`와 같다). 자산이 둘 이상이거나 USD 자산이 섞이면 통화별 키가 함께 늘어난다 — 예시는 `frontend/src/api/fixtures.ts`의 `portfolioFixture` 참고.

`totalCostByCurrency`가 통화별 Map인 이유: 취득원가 합계를 단일 `totalCostKrw`로 두면 USD 자산이 섞였을 때 환율 없이는 정확한 값을 낼 수 없다. Task 023(환율 연동) 전까지 거짓 숫자를 내보내지 않기 위해 통화별로 나눠 담는다.

`evaluationKrw`뿐 아니라 `unrealizedPnl`(`PortfolioItem`·`totalUnrealizedPnl` 둘 다)도 **항상 KRW 환산액**이다 — 원자산 통화(`currency`)가 아니다. PRD F005가 "전체 자산을 KRW 기준으로 환산해 평가금액·비중(%)·미실현 손익 표시"라고 명시하므로, 세 값(평가금액·비중·손익) 모두 같은 환산 기준을 따른다. `evaluationKrw`만 필드명에 `Krw`가 붙어 있어 헷갈리기 쉬우니, Task 023 구현 시 `unrealizedPnl`도 동일하게 KRW 스케일(정수)로 계산해야 한다.

### 시뮬레이터 응답 예시

```json
{
  "currentAvgPrice": "60000",
  "expectedAvgPrice": "58333",
  "expectedQuantity": "15.00000000",
  "currentWeight": null,
  "expectedWeight": null,
  "calculatedAt": "2026-08-05T10:00:00.003Z"
}
```

**골든 케이스**: 기존 평단 60,000원 × 10주 + 추가 55,000원 × 5주 → `(600,000 + 275,000) / 15 = 58,333.33...` → HALF_UP, scale 0 → **58,333원**. `expectedQuantity`는 추가 매수 반영 후 총 보유 수량(10 + 5 = 15)이며, 통화/자산 종류와 무관하게 항상 8자리 scale로 내려온다. 백엔드 Task 015(시뮬레이터 API) 구현 시 이 필드를 포함해야 한다.

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

**구현된 코드 (Task 006)**: `ASSET_NOT_FOUND`(404, 존재하지 않거나 타 유저 소유 — 403 대신 404로 ID 유출 방지), `HOLDING_CONFLICT`(409, 낙관적 잠금 `version` 불일치. `ObjectOptimisticLockingFailureException` 캐치), `CONFLICT`(409, `uk_users_email` 외의 데이터 무결성 제약 위반 — 매칭되는 도메인 에러 코드가 없을 때의 일반 폴백)

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

## 확정된 설계 결정 (Task 006, 구 「착수 전 결정 사항」)

| # | 쟁점 | 확인된 사실 | 확정 |
|---|---|---|---|
| 1 | CASH 자산 등록 | `holdings`에 `CHECK (avg_price > 0)` — 현금에는 평단가 개념이 없어 `0`을 넣으면 INSERT 실패 | 화면에서 평단가 입력란을 숨기고, `CreateAssetRequest.avgPrice`는 CASH일 때 `null`을 허용한다. 서버는 `assetType == CASH`면 `avgPrice=1`을 강제 삽입한다(`AvgPriceRequiredUnlessCash` 클래스 레벨 검증, Task 012에서 구현). CHECK 제약은 그대로 둔다 — 완화하면 STOCK/COIN의 0 평단가 오입력까지 함께 통과하게 된다 |
| 2 | `transactions` 기록 여부 | F001(자산 등록)·F003(자산 수정) 어디에도 `transactions` INSERT 경로가 정의돼 있지 않음 | 자산 **등록 시에만** `BUY` 1건을 자산·Holding과 같은 트랜잭션으로 기록한다(Task 012). 보유 **수정**(F003)은 기록하지 않는다 — 수정은 실매매가 아닌 오류 정정일 수도 있어, 수정을 그대로 거래로 남기면 이력이 실제 매매와 달라진다. Task 024가 사용자 직접 거래 입력 API로 이 갭을 메운다 |
| 3 | 종목 중복 등록 | `assets`에 `(user_id, ticker)` UNIQUE 없음 — 같은 종목을 여러 번 등록 가능 | **허용한다.** UNIQUE 제약을 추가하지 않으므로 이 Task에 Flyway 마이그레이션이 없다. 총 자산 화면(구 포트폴리오 화면)은 같은 티커가 여러 줄로 나타날 수 있음을 전제로 설계한다(Task 009, 투자/현금 자산 그룹 안에서도 별도 행 유지) |
| 4 | 자산 삭제 시 이력 소실 | `holdings`·`transactions`의 FK가 `ON DELETE CASCADE` — 자산 삭제(F004) 시 거래 이력도 함께 사라짐 | 하드 삭제를 유지한다(소프트 삭제로 전환하지 않음). Task 011의 삭제 확인 팝업에 "보유 정보와 거래 이력이 함께 삭제되며 복구할 수 없습니다" 경고 문구를 포함해 최소 완화한다 |

---

## 리스크 & 완화 전략

| 리스크 | 완화 |
|---|---|
| BigDecimal 스케일 규칙 누락 | 위 「금융 정밀도 규칙」 표를 `BigDecimalPrecisionTest`로 상수화, 모든 서비스 로직에 재사용 |
| Optimistic Lock 고빈도 충돌 | 유저 수 적은 초기 단계이므로 단순 `@Version`으로 충분. 충돌 시 `HOLDING_CONFLICT` 409 응답으로 클라이언트가 재조회 후 재시도 |
| 프론트-백엔드 API 계약 어긋남 | Task 006에서 TypeScript 타입(`frontend/src/api/types.ts`)과 더미 픽스처(`frontend/src/api/fixtures.ts`)를 계약의 단일 출처로 삼아 양쪽이 참조. 백엔드는 `ApiContractSerializationTest`로 직렬화 형태를 고정 |
| 종목 중복 등록 허용에 따른 포트폴리오 집계 복잡도 | 「확정된 설계 결정」 #3 참조 — 티커별 합산이 아닌 자산(행) 단위 집계로 설계해 복잡도를 피한다 |

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
