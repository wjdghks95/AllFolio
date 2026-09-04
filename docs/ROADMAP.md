# AllFolio 개발 로드맵

**최종 수정:** 2026-09-04
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
  - ✅ 남은 갭 해소: Refresh Token 부재로 15분마다 재로그인해야 했던 문제는 Task 019에서 해소됨

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

### Phase 2: UI/UX 완성 + 백엔드 도메인 구현 (병렬 2트랙) ✅ 완료 (2026-08-27)

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

- **Task 010: 자산 등록 화면 구현 (F001)** ✅ — 완료 (2026-08-21)
  - ✅ `frontend/src/pages/AssetNewPage.tsx` — 유형(STOCK/COIN/CASH)·티커·종목명·통화·수량·평단가 6필드 폼. `lib/validation.ts`의 기존 검증 함수(Task 007에서 이미 구현)를 그대로 연결, 신규 검증 로직 없음
  - ✅ `frontend/src/components/SegmentToggle.tsx`(구 `AssetTypeToggle.tsx`, code-reviewer 지적 반영 시 일반화) — 고정 소수 선택지 세그먼트 토글(세 칸이 테두리를 공유, 선택 상태는 색+굵기 이중 신호). 자산 유형(3지선다)·통화(2지선다) 두 필드가 이 컴포넌트를 공유한다. CASH 선택 시 평단가 입력란이 렌더 트리에서 사라지고 값도 `null`로 리셋 — 서버가 `avgPrice=1`을 고정 삽입하므로 프론트는 요청 본문에서 `avgPrice`를 생략(`null`)하면 된다 (Task 006 결정 #1)
  - ✅ 백엔드 자산 등록 API(Task 012)가 아직 없어 실 API 호출 없이 클라이언트 검증만 통과하면 성공으로 간주 — 제출 성공 시 `navigate('/portfolio', { state: { flash } })`로 이동, `PortfolioPage`가 `Alert`로 렌더링. Task 007이 정한 "토스트 대신 라우터 state로 결과 전달" 패턴의 첫 실제 적용 사례
  - ✅ 구현 중 발견한 버그 수정: `PortfolioPage`의 flash 배너가 `location.state`를 매 렌더 다시 읽는 구조라 마운트 직후(사실상 한 프레임 만에) 사라지던 결함을 `useState` 지연 초기화로 마운트 시점 1회 캡처하도록 고쳐, 자동 소멸 타이머 없이 화면에 남아 있게 함(Task 007 결정과 정합)
  - ✅ `AssetNewPage.test.tsx`(신규 5케이스) + `PortfolioPage.test.tsx`(flash 관련 2케이스 추가, 기존 10케이스 유지) — `useNavigate` 목킹 대신 `MemoryRouter` + 더미 라우트로 실제 이동을 검증하는 기존 컨벤션(`LoginPage.test.tsx`) 그대로 따름
  - ✅ 시각·카피 다듬기(ui-ux-designer) — `docs/DESIGN.md` §6-3 "폼 화면 골격" 신설(v1.5.0): 폼 폭 448px 제한, 필드를 "자산 정보"/"보유 정보" 2묶음으로 분할, 조건부 필드(CASH 평단가)는 빈자리 대신 안내 밴드로 대체, flash 배너는 `h1` 위 본문 최상단에 배치
  - ✅ code-reviewer 독립 검증 후속 수정(Major 1건 + Minor 6건, 2026-08-21) — 통화 필드를 PRD F001 "KRW/USD 선택"에 맞춰 자유 입력(`TextField`)에서 `SegmentToggle` 기반 2지선다로 수정(Major). `AssetTypeToggle`을 제네릭 `SegmentToggle`로 일반화해 자산 유형·통화 두 필드가 공유. CASH↔STOCK 전환 시 평단가뿐 아니라 평단가 에러도 함께 리셋, `PortfolioPage`의 `Flash` 타입을 export해 `AssetNewPage`가 재사용, history 정리 effect의 `navigate` 호출에 `location.search` 보존. `AssetNewPage.test.tsx`·`PortfolioPage.test.tsx`에 회귀 케이스 각 1건 추가
  - ✅ code-reviewer 최종 통짜 재검증 후속 수정(Minor 11건, 2026-08-21) — `SegmentToggle`의 `data-testid` 접미사를 `option.value` 자동 파생(대문자 섞임)에서 호출부가 명시하는 kebab-case `testIdSuffix`로 변경, `SegmentToggle.test.tsx` 신규(3케이스), `ariaLabel`을 `aria-labelledby` 연결(`ariaLabelledBy` prop)로 교체, 도달 불가능한 `validateCurrency` 분기 제거, history 정리 effect의 `deps`를 exhaustive하게 채우고 근거가 부정확했던 oxlint 억제 주석 제거(실측 결과 억제 없이도 재실행 문제 없음 확인 — 앞 항목의 "억제 주석 유지" 판단을 이번에 재검토해 뒤집음). `AssetNewPage.test.tsx`에 CASH↔STOCK 왕복 시 평단가 에러 미재노출 케이스 추가(총 7케이스), 테스트 헬퍼명 `fillValidStockFields`→`fillCommonFields`
  - ⚠️ **후속 과제 (백로그, 사용자 요청 2026-08-21)**: 티커·종목명 입력을 자유 텍스트가 아닌 **검색(자동완성)** 방식으로 바꾸고 싶다는 요청이 있었으나, 데이터 출처에 따라 구현 범위가 크게 달라져(프론트 내장 정적 목록 vs 신규 백엔드 검색 API vs 외부 시세 API 연동) 착수를 보류했다. 외부 시세 연동은 이미 Phase 3 Task 021 범위이므로, 착수 시점에 (a) 프론트 정적 목록으로 우선 자동완성만 붙일지 (b) Task 021과 묶어 실 검색으로 바로 갈지부터 정해야 한다

- **Task 011: 자산 상세 화면 구현 (F002·F003·F004·F006·F007)** ✅ — 완료 (2026-08-21)
  - ✅ `frontend/src/pages/AssetDetailPage.tsx` — 상세 정보, 차트 영역(정적 더미), 물타기 시뮬레이터 폼, 수정 폼, 삭제 확인 팝업. Task 007~010과 동일하게 백엔드 API(Task 012·013·015) 미착수 상태라 실 호출 없이 `frontend/src/api/fixtures.ts` 더미 데이터와 `frontend/src/lib/simulate.ts` 순수 계산만으로 완성
  - ✅ 삭제 확인 팝업에 "보유 정보와 거래 이력이 함께 삭제되며 복구할 수 없습니다" 경고 문구 포함 (Task 006 결정 #4, `ConfirmDialog` description에 그대로 사용)
  - ✅ 수정 폼은 상세 조회 응답의 `version` 값을 화면에 노출하지 않고 컴포넌트 상태로만 들고 있다가 `UpdateHoldingRequest` 조립에 그대로 실어 보낸다. 백엔드가 없어 실제 `PUT` 전송은 하지 않으며, 409 `HOLDING_CONFLICT` 분기는 이번엔 만들지 않았다(일어날 수 없는 실패를 흉내내는 코드를 피하기 위함) — **Task 018(실 연동) 착수 시 이 화면에 폼 상단 Alert로 추가**(§6-1 인증 화면 패턴 재사용 예정)
  - ⚠️ 남은 갭 (2차 code-reviewer 검증에서 발견, 2026-08-21): 수정 폼·시뮬레이터 상태(`editQuantity`/`editAvgPrice`/`simResult`)가 컴포넌트 마운트 시점에만 초기화된다. 지금은 상세 화면끼리 직접 이동하는 경로가 없어 도달 불가능하지만, **Task 018·020에서 상세→상세 이동 경로(예: 관련 종목 링크)가 생기면 라우터 `key={id}` 리마운트 또는 `id` 변경 시 폼 재초기화를 반드시 추가해야 한다** — 그렇지 않으면 이전 자산의 수량·평단가로 `PUT` 요청이 조립되는 상태 불일치가 생긴다
  - ✅ **착수 전 확인 필요 (Task 009 재개편 후속) — 해소**: 평단가·취득원가·비중(%)을 이 화면에서 구하는 방법으로 (a) 상세 진입 시 `GET /v1/portfolio`를 다시 불러 해당 항목을 찾는 방식을 확정(사용자 승인, 2026-08-21). (b) 라우터 state 전달은 새로고침·URL 직접 접근 시 값이 사라져 배제, (c) `AssetResponse`에 `weight` 추가는 단일 자산 응답에 포트폴리오 전체 집계값이 섞여 책임이 흐려지고 Task 012 계약을 다시 열어야 해 배제. 더미 데이터 단계에서는 `assetListFixture`(수량·평단가·`version`)와 `portfolioFixture.items`(취득원가·평가금액·평가손익·비중)를 각각 `id`/`assetId`로 조회해 병합하며, Task 018에서 이 두 줄이 `GET /v1/assets/{id}` + `GET /v1/portfolio` 두 번의 fetch로 치환된다
  - ✅ `frontend/src/pages/AssetDetailPage.test.tsx` — 14케이스(상세정보·BTC로 취득원가 병합·코인 스케일 회귀(같은 테스트 1건)·null 필드 표시·존재하지 않는 id 안내·CASH 분기 3필드·시뮬레이터 계산·시뮬레이터 stale 결과 무효화 2건(검증 실패/입력 변경)·수정 검증/성공·삭제 취소/확인·수량 0 가드(실제 미처리 예외 0건 단언 포함)·`portfolioItem` 부재 회귀)
  - ✅ `frontend/src/lib/messages.ts`의 `ASSET_NOT_FOUND` 문구를 "포트폴리오에서" → "총 자산 화면에서"로 수정 (Task 009 화면 명칭 변경("포트폴리오"→"총 자산") 이후 유일하게 남아있던 잔여 문구 정리, 키는 그대로 유지)
  - ⚠️ 남은 갭 2건 (전체 코드·문서 종합 검증에서 발견, 2026-08-21)
    - 취득원가(`cost`) 표시 스케일이 어느 문서에도 명시돼 있지 않다. `AssetDetailPage.tsx`는 `avgPrice`/`quantity`와 마찬가지로 `cost`도 자산 자체의 통화·유형 스케일(`scaleFor({currency, assetType})`, COIN이면 8자리)로 표시한다 — `evaluationKrw`/`unrealizedPnl`/`weight`만 예외적으로 항상 KRW/비중 스케일로 강제 표시된다(Task 009 결정). `frontend/src/api/fixtures.ts`의 `portfolioFixture` BTC 항목도 이미 이 규칙(코인 스케일 8)로 작성돼 있어 새 버그는 아니지만, 아래 「금융 정밀도 규칙」 표에는 반영되지 않았다 — Task 013(포트폴리오 API) 착수 시 서버 응답도 이 규칙을 따라야 한다
    - `POST /v1/simulate/avg-price`의 `additionalQuantity`는 백엔드 계약상 `> 0` 필수(`SimulateAvgPriceRequest`의 `@DecimalMin(value="0", inclusive=false)`)이지만, `AssetDetailPage.tsx`의 시뮬레이터 입력은 기존 `validateQuantity`(`≥ 0`)만 재사용해 `0`을 통과시킨다. 지금은 보유수량 0 자산에 추가수량 0을 입력하는 조합만 별도 가드로 막아뒀지만, Task 018 연동 시 추가수량 0 단독 입력도 400 `VALIDATION_ERROR`가 난다 — 그때 시뮬레이터 전용 검증(추가 수량 `> 0`)을 추가해야 한다
  - ✅ 시각·카피 다듬기(ui-ux-designer) — `docs/DESIGN.md` §6-4 "상세 화면 골격" 신설(v1.6.0). Playwright MCP 실측으로 버그 3건 발견·수정: 예상 평단선이 실제 손익 방향과 반대로 그려지던 문제(방향에 따라 고정 좌표 2개 중 하나를 선택하도록 수정), 375px 화면에서 변화량 라벨이 뱃지와 겹치던 문제, CASH 자산에서 의미 없는 "평단가 1"·취득원가 행이 노출되던 문제(CASH에서 평단가 행·취득원가 행·차트 카드를 모두 숨김)
  - ✅ code-reviewer 독립 검증 1차 — Major 1건(시뮬레이터 검증 실패·입력 변경 후에도 이전 계산 결과가 화면에 남던 결함) + Minor 7건(CASH 테스트 단언 보강, 매칭 로직 회귀 테스트 추가, `portfolioItem`만 없을 때 오판정하던 not-found 조건을 `!asset` 단독으로 수정, 불필요한 `version` `useState` 제거, 보유수량 0 자산의 물타기 시뮬레이터 크래시 가드 추가, not-found 화면에 `h1` 추가, 본 문서 갱신) 전부 수정. Blocker 0건
  - ✅ code-reviewer 독립 검증 2차(1차 수정분 전체를 뮤테이션 테스트로 재검증, 2026-08-21) — Blocker 0건·Major 0건(1차 Major가 실제로 고쳐졌음을 뮤테이션으로 확인)이었으나, 1차에서 고친 것 중 4곳이 **"고쳤지만 회귀 테스트가 그 수정을 붙잡지 못하는" 상태**였음을 뮤테이션 테스트로 발견: (1) 검증 실패 분기의 `setSimResult(null)`이 실은 입력 변경 시 이미 결과가 지워져 도달 불가능한 죽은 코드였음 → 제거(CLAUDE.md §2), (2) 수량 0 가드 테스트가 가드를 지워도 통과해 크래시를 실제로 검증하지 못함 → `window` error 이벤트로 미처리 예외 0건을 단언하도록 보강, (3) `portfolioItem`만 없는 경우의 not-found 오판정 방지 로직에 회귀 테스트가 없었음 → `vi.doMock`으로 `portfolioFixture.items`를 비운 케이스 추가, (4) "BTC로 매칭 키 회귀"라던 1차 테스트가 실제로는 index 우연 일치 사각지대를 닫지 못함(BTC도 두 fixture에서 우연히 같은 index) → 문구를 실제 커버리지(하드코딩 실수 검출)로 정정. 4건 모두 가드/조건을 임시로 되돌려 새 테스트가 실패하는지 확인 후 원복하는 방식으로 뮤테이션 검증 완료

**2-B. 백엔드 도메인 로직**

- **Task 012: 자산 CRUD API 구현 (F001~F004)** ✅ — 완료 (2026-08-24)
  - ✅ 5개 엔드포인트(`POST /v1/assets`·`GET /v1/assets`·`GET /v1/assets/{id}`·`PUT /v1/assets/{id}/holdings`·`DELETE /v1/assets/{id}`) 구현. `AssetController`(`Authentication.getName()`→`UUID` 파싱)+`AssetService`(생성자 주입, 메서드 단위 `@Transactional`) 2계층으로 구성
  - ✅ 유저 소유권 격리 — `findByIdAndUser_Id`로 조회한 뒤 없으면 `AssetNotFoundException`(404 `ASSET_NOT_FOUND`). 조회·수정·삭제 3개 엔드포인트 전부 동일하게 적용, 타 유저 소유 자산은 403이 아닌 404로 응답해 ID 유출을 막는다
  - ✅ 자산 생성 시 `Asset`+`Holding`+`Transaction`(BUY 1건)을 단일 `@Transactional` 메서드에서 함께 저장(Task 006 결정 #2). 보유 수정(PUT)은 `transactions`를 기록하지 않는다
  - ✅ 종목 중복 등록 허용 — `(user_id, ticker)` UNIQUE 제약 없음, 신규 Flyway 마이그레이션 없이 완료(Task 006 결정 #3)
  - ✅ CASH 자산은 생성·수정 어느 경로든 요청의 `avgPrice`와 무관하게 항상 `1`로 강제(Task 006 결정 #1)
  - ✅ 커서 페이지네이션 — `AssetRepository`에 `findByUser_IdOrderByIdDesc`/`findByUser_IdAndIdLessThanOrderByIdDesc` 2종 추가, `limit+1`건 조회로 다음 페이지 존재 여부 판단(별도 COUNT 쿼리 없음). UUID v7의 시간순 단조 증가 특성 덕에 `id DESC` 정렬만으로 최신 등록순이 보장됨. `HoldingRepository.findByAsset_IdIn` 배치 조회로 목록 조회의 N+1 방지
  - ✅ 낙관적 잠금(Optimistic Lock) 수동 검증 — `PUT`은 같은 트랜잭션에서 방금 읽은 `holding.getVersion()`이 항상 최신값이라 Hibernate 자동 `@Version` 검사만으론 "클라이언트가 과거에 읽은 값" 기준 충돌을 잡지 못한다. `AssetService.updateHolding`이 `holding.getVersion() != request.version()`을 명시 비교해 다르면 `ObjectOptimisticLockingFailureException`을 직접 던져 409 `HOLDING_CONFLICT`로 응답. `holding.update()` 직후 `holdingRepository.flush()`를 호출해 응답의 `version`이 증가된 값으로 나가도록 보장(최초 구현 시 flush 누락으로 응답에 증가 전 값이 나가던 버그를 실제 curl 수동 검증 중 발견해 수정함)
  - ✅ PUT 대상이 CASH가 아닌데 `avgPrice`가 `null`이면 신규 `AvgPriceRequiredException` → 400 `VALIDATION_ERROR`(신규 에러 코드 추가 없이 기존 코드 재사용, 메시지는 `AvgPriceRequiredUnlessCash` 기본 문구와 동일하게 맞춰 POST/PUT 문구 일치)
  - ✅ **Task 005 리뷰 후속 조치 (반영 완료)**
    - `AssetRepository.findAllByUser_Id`를 위 커서 페이지네이션 시그니처 2종으로 대체
    - `AssetResponse.from(Asset, Holding)` 팩토리 삭제, 엔티티→DTO 매핑 로직을 `AssetService`의 private `toResponse()` 헬퍼로 이동(4개 메서드가 공유). DTO는 순수 값 생성자만 남음
  - ✅ **Task 006 리뷰 후속 조치 (반영 완료)**
    - `ApiContractSerializationTest`에 `AvgPriceRequiredUnlessCash` 검증, `SimulateAvgPriceResponse.currentWeight/expectedWeight` null 키 보존, `AssetListResponse`/`PortfolioResponse` 직렬화, 요청 방향(`"60000"` 문자열 → `BigDecimal`) 역직렬화 테스트 5종 추가(기존 5종과 합쳐 총 10케이스)
    - `GlobalExceptionHandler.handleDataIntegrityViolation`을 원인 메시지 문자열 매칭에서 `org.hibernate.exception.ConstraintViolationException#getConstraintName()` 기반 판별로 전환(Bean Validation의 동명 `jakarta.validation.ConstraintViolationException`과 혼동 주의)
  - ✅ `AssetIntegrationTest` 신규(Testcontainers, 15케이스) — 5개 엔드포인트 정상/예외 동작 전체 검증. 소유권 격리·낙관적 잠금·CASH 강제 3가지는 코드를 일부러 무력화해 테스트가 실제로 실패를 잡는지 뮤테이션 검증까지 수행(code-reviewer가 Task 009·011에서 "고쳤지만 테스트가 못 잡는" 사례를 발견했던 전례에 따른 절차)
  - ✅ Task 003 때 "`/v1/assets`가 없어 404가 나면 인증 통과 증거"라는 전제로 작성된 `AuthIntegrationTest`의 플레이스홀더 테스트(`protectedEndpointWithValidTokenPassesAuthentication`)가 이번에 그 경로가 실제 라우트가 되며 깨져, 대상 경로를 존재하지 않는 `/v1/nonexistent`로 교체해 원래 검증 의도를 유지
  - ✅ senior-backend 에이전트의 독립 재검토(T1~T4 완료 후 1회) — 수정 필요 항목 없음으로 확인
  - ✅ code-reviewer 에이전트의 독립 검증(T1~T7 전체 완료 후, 실제 서버 기동 + curl/SQL 로그 실측) — Blocker 0건. Major 2건 발견:
    - **(수정 완료)** `GET /v1/assets`의 `limit`이 `@Min(1)`/`@Max(100)` 범위를 벗어나면 400이 아닌 500이 나가던 결함. 원인: `AssetController`의 클래스 레벨 `@Validated`가 Spring 7의 내장 메서드 파라미터 검증(→`HandlerMethodValidationException`→400)을 끄고 구식 AOP 경로(`jakarta.validation.ConstraintViolationException`)로 전환시키는데, 그 예외가 `GlobalExceptionHandler`에 매핑돼 있지 않아 500 폴백으로 샜다. `@Validated` 제거로 해결(제거해도 `@Min`/`@Max` 검증 자체는 내장 경로로 그대로 동작). `listAssetsWithLimitBelowMinimumReturnsValidationError`/`...AboveMaximumReturnsValidationError` 회귀 테스트 추가
    - **(보류, 사용자 확인 완료)** 같은 자산인데 `POST`/`PUT` 응답은 요청 스케일 그대로(`"10"`), `GET`(단건·목록)은 DB `NUMERIC(28,8)` 왕복 후 스케일 8 고정(`"10.00000000"`)으로 서로 다르게 직렬화된다. ROADMAP 위 API 예시(`"quantity": "10"`)와 실제 GET 응답이 어긋난다. 수치적으로는 동일한 값이고 프론트가 `big.js`로 재포맷하므로 기능상 즉시 문제는 아니나, 문자열 비교(캐시 키·변경 감지)에는 영향을 줄 수 있다. 처리 방향(GET 스케일로 통일 vs 자산 유형별 「금융 정밀도 규칙」 스케일 적용)은 아직 미정 — 다음 착수 시 결정
    - Minor 5건(목록 조회 시 Holding 결측 시 무방비 NPE 가능성, `limit`/GET 수치값/목록 소유권 격리/DELETE CASCADE 실제 확인/POST 클래스 레벨 검증의 HTTP 경로 등 테스트 커버리지 공백, `AvgPriceRequiredException` 메시지와 `AvgPriceRequiredUnlessCash` 기본 메시지의 결합을 잡는 테스트 부재, `PortfolioResponse`의 `totalEvaluationKrw`/`totalUnrealizedPnl` null 키 보존 미검증)는 이번엔 보류 — Task 013 착수 전 재검토
  - ⚠️ 남은 갭 (code-reviewer 검증으로 서술 정정): `AssetService.createAsset()`이 `userRepository.getReferenceById(userId)`로 유저를 프록시 참조한다. JWT는 유효하지만 그 사이 유저 레코드가 삭제된 극단적 동시성 케이스에서는 `assets.user_id` FK 위반으로 `DataIntegrityViolationException` → **409 `CONFLICT`**가 나간다(이전 버전에 적었던 "500 가능성"은 부정확한 추정이었음 — 프록시가 초기화되지 않아 `EntityNotFoundException` 경로를 타지 않는다). 발생 가능성이 낮고 명세에도 없어 이번 Task에서는 손대지 않았다

- **Task 013: 포트폴리오 홈 API 구현 (F005a)** ✅ — 완료 (2026-08-25)
  - ✅ `GET /v1/portfolio` 구현. `PortfolioController`+`PortfolioService` 2계층으로 `AssetService`(CRUD)와 책임 분리 — Task 023(외부 시세 연동)이 `evaluationKrw`/`unrealizedPnl`/`weight`를 채울 때 이 클래스만 건드리면 되도록 경계를 미리 나눔
  - ✅ `AssetRepository.findByUser_Id` 신규 추가(커서 페이지네이션 없는 전체 조회 — `GET /v1/assets`의 페이지네이션 조회와 목적이 다름), `HoldingRepository.findByAsset_IdIn` 배치 조회로 N+1 방지(Task 012 패턴 재사용, 자산 8건으로 실측해도 쿼리 2회 고정 확인)
  - ✅ 취득원가(`cost = quantity × avgPrice`)에 자산유형/통화별 정밀도 규칙 적용 — COIN은 통화 무관 8자리, 그 외엔 통화 기준(KRW 0/USD 4), HALF_UP. Task 011이 남겨뒀던 갭("Task 013 착수 시 서버 응답도 이 규칙을 따라야 한다") 해소
  - ✅ `totalCostByCurrency`(통화별 취득원가 합계) 필드는 사용자 확정 결정으로 유지(제거하지 않음, Task 009가 남긴 재검토 항목 해소)
  - ✅ `evaluationKrw`/`unrealizedPnl`/`weight`/`totalEvaluationKrw`/`totalUnrealizedPnl` 5개 필드는 항상 `null`(Task 023 범위), 응답 JSON에서 키 자체는 유지됨을 검증
  - ✅ 소유권 격리 — `findByUser_Id`로 요청자 본인 자산만 조회, 실측(유저 A 8건/B 1건)으로 상대 자산이 안 섞임을 확인
  - ✅ `quantity`/`avgPrice` 스케일 결정(Task 012가 "다음 착수 시 결정"으로 미뤄둔 항목) — `GET /v1/portfolio`에 한해 `quantity`는 자산유형·통화 무관 항상 8자리(시뮬레이터 `expectedQuantity`와 동일 규칙), `avgPrice`는 `cost`와 동일한 스케일 규칙(COIN 8자리, 그 외 통화 기준)로 정규화. **단 `GET /v1/assets`(Task 012)는 이번에 손대지 않아 여전히 DB `NUMERIC(28,8)` 왕복 스케일(8자리 고정)을 그대로 노출** — 같은 `quantity`/`avgPrice`라도 두 엔드포인트의 표기 스케일 결정 기준이 다르다(아래 「남은 갭」 참고)
  - ✅ `PortfolioIntegrationTest` 신규(Testcontainers, 5케이스) — 빈 포트폴리오, 자산유형·통화 조합별 원가 집계(동일 티커 중복 등록 허용 검증 겸용), 미구현 평가지표 5개 필드의 null 키 보존, 소유권 격리, 무인증 401
  - ✅ code-reviewer 독립 검증(실제 서버 기동+curl 실측, N+1 실측 포함) — Blocker 0건. Major 4건 발견:
    - **(수정 완료)** `totalCostByCurrency` 합계에 통화 스케일이 적용되지 않아 항목별 `cost`와 표기가 어긋나던 결함(예: KRW 합계가 소수 2자리로 나감) — `stripTrailingZeros()` 대신 `scaleForCurrency(currency)` 기반 명시 반올림(HALF_UP)으로 수정
    - **(수정 완료)** 위 결함을 테스트가 못 잡던 문제 — 합계 검증을 `BigDecimal.compareTo`에서 정확한 문자열 비교로 강화, 뮤테이션 테스트(고의로 `stripTrailingZeros()` 버전으로 되돌려 테스트가 실패하는지 확인)로 검출력 실증
    - **(수정 완료)** `quantity`/`avgPrice` DB 왕복 스케일 노출(Task 012 이월 항목) — 위 결정대로 `GET /v1/portfolio`에 한해 정규화 적용
    - **(별도 태스크로 분리)** `./gradlew test` 전체 스위트 실행 시 간헐적 실패 — 원인은 이번 Task 로직이 아니라 `AbstractIntegrationTest`의 Testcontainers `static @Container` 공유 구조(통합 테스트 클래스가 4개로 늘며 처음 노출된 기존 인프라 결함). 각 클래스 단독 실행은 항상 통과. 별도 태스크로 트래킹
    - Minor 7건 중 `holding == null` 방어 코드(Asset+Holding이 항상 단일 트랜잭션에서 생성돼 도달 불가능, CLAUDE.md 「불가능한 시나리오 에러 처리 금지」) 제거 반영. 나머지는 보류 — 상세는 아래 「남은 갭」
  - ✅ code-reviewer 2차 독립 검증(M1~M4 수정분 재검증, 실제 서버 기동+curl 실측·N+1 재실측·`AbstractIntegrationTest` Singleton Container 전환 부작용 점검 포함) — Blocker 0건. `./gradlew test --rerun` 4회 연속 66개 테스트 전부 통과 재확인. 신규 Major 1건 발견:
    - **(문서로 해결)** `avgPrice`가 M4로 반올림되면서 `cost`(반올림 전 원본 정밀도로 계산)와 `quantity × 응답의 avgPrice`가 반올림 오차만큼 어긋나게 됨 — 코드는 그대로 두고 위 「`GET /v1/portfolio` 응답 예시」 절에 예외로 명문화(사용자 결정: 화면 표시값엔 영향 없고 코드 수정 비용 대비 실익이 낮다고 판단)
    - Minor 2건 추가 발견: (a) `quantity`/`avgPrice`가 테스트로 전혀 검증되지 않아 위 Major를 테스트가 못 잡았음 — `PortfolioIntegrationTest`에 항목별 스케일 단언과 소수 평단가 회귀 케이스 추가로 해결. (b) `items` 배열 순서가 문서에 정의돼 있지 않던 문제 — `AssetRepository.findByUser_Id`를 `findByUser_IdOrderByIdDesc`(단일 인자 오버로드)로 바꿔 `GET /v1/assets`와 동일하게 id DESC(최신 등록순) 보장으로 해결
  - ⚠️ 남은 갭:
    - `GET /v1/assets`(Task 012)와 `GET /v1/portfolio`(Task 013)의 `quantity`/`avgPrice` 스케일 표기 기준이 서로 다르다(위 서술) — 두 엔드포인트를 통일할지는 다음 착수 시 재검토(Task 016에서 회귀 테스트로 현재 동작을 스냅샷 고정, 처리 방향 자체는 여전히 미정)
    - ✅ (Task 016에서 해소) KRW/USD 외 통화(예: JPY)로 자산을 등록하면 `scaleFor()`가 스케일 2로 폴백하는 문제 — 아래 「금융 정밀도 규칙」 표에 폴백 행 추가로 해소
    - ✅ (Task 016에서 해소) 정밀도 스케일 로직이 `PortfolioService.scaleFor`/`SimulateAvgPriceResponse`/프론트 `lib/big.ts` 3곳에 흩어져 있던 문제 — 백엔드 2곳은 `domain/PrecisionScale`로 통합(프론트는 언어 경계라 별개 유지, Task 007이 이미 `lib/big.ts`에 별도 상수화를 해둔 상태)

- **Task 014: Observability 최소 셋업** ✅ — 완료 (2026-08-25)
  - ✅ `logback-spring.xml` 신규 작성 — 콘솔 로그를 `LogstashEncoder`로 JSON 출력(의존성은 이미 있었으나 설정 파일이 없어 미사용 상태였음). Boot 기본 노이즈 억제 로거를 `<include resource="org/springframework/boot/logging/logback/defaults.xml"/>`로 가져옴. `<root>` level은 `INFO` 리터럴 — `application.yml`의 `logging.level.*`가 부팅 후 항상 덮어쓰므로(`LOGGING_LEVEL_ROOT` 환경변수도 Spring relaxed binding으로 결국 같은 경로라 마찬가지) 여기 값은 어떤 경로로도 최종 레벨에 영향을 못 준다. 한때 환경변수 폴백(`${LOGGING_LEVEL_ROOT:-INFO}`)으로 바꿔봤으나 실효 없는 죽은 설정임을 2차 code-reviewer가 지적해(CLAUDE.md §2 요청되지 않은 configurability 금지) 리터럴로 되돌림
  - ✅ `infra/logging` 패키지 신규 — `LogMarkers`(`AUDIT` 마커 상수), `MdcKeys`(`traceId`/`userId` 키 리터럴을 상수로 빼 두 필터가 공유), `MdcFilter`(요청마다 `traceId` 발급, `finally`에서 `MDC.clear()`로 Virtual Thread 재사용 시 누수 방지)
  - ✅ `AuthService`에 `AUDIT` 로깅 추가 — 회원가입 성공·로그인 성공·로그인 실패(이메일 없음/비밀번호 불일치 사유 구분 없이 동일 메시지, 기존 user enumeration 방지 원칙을 로그에도 유지) 3개 지점. 이메일 등 PII는 로그 인자로 절대 넘기지 않고 `userId`(UUID)만 사용 — 실측(전체 로그에서 이메일 문자열 0건)으로 확인
  - ✅ `MetricsIntegrationTest` 신규 — `application.yml`의 `management.metrics.distribution.percentiles-histogram.allfolio.simulation.duration: true` 설정이 실제로 프로메테우스 히스토그램 버킷을 만들어내는지, `MeterRegistry`로 직접 샘플을 기록해 검증(Task 015 시뮬레이터가 아직 없어 대체 검증 — 실측으로 `le="0.001"`~`le="+Inf"` 69개 버킷 생성 확인). `le="0.005592405"` 버킷은 있지만 정확히 5ms 경계(`0.005`)에 딱 맞는 버킷은 없어(`0.004194304`→`0.005592405`로 건너뜀) Task 015의 P99 ≤5ms 판정은 `histogram_quantile`의 선형보간에 의존하게 된다 — 정확히 5ms 경계로 끊고 싶으면 Task 015 착수 시 `management.metrics.distribution.slo.allfolio.simulation.duration: 5ms` 추가를 검토(2차 code-reviewer 지적, 이번엔 불필요)
  - ✅ code-reviewer 독립 검증(실제 서버 기동 + curl 실측, JSON 로그 라인 단위 실측 포함) — Blocker 0건. Major 1건 발견:
    - **(수정 완료)** `MdcFilter`를 `@Component`로만 등록해뒀더니, 스프링 부트가 일반 필터를 기본 순서(`LOWEST_PRECEDENCE`)로 등록하는 반면 Spring Security 필터 체인은 `SecurityProperties.DEFAULT_FILTER_ORDER = -100`으로 훨씬 이르게 등록돼, `MdcFilter`가 시큐리티 체인 *뒤에서* 실행되고 있었다. 그 결과 `JwtFilter`/`JwtIssuer`의 인증 진단 로그("JWT 파싱 실패" 등)에는 `traceId`가 전혀 붙지 않는 사각지대가 있었다(401 원인 조사 시 다른 로그와 상관관계를 못 지음). `MdcFilter`에 `@Order(Ordered.HIGHEST_PRECEDENCE)`를 추가해 시큐리티 체인보다 먼저 실행되게 하고, `userId`를 채우는 책임을 `MdcFilter`(SecurityContext 읽기)에서 `JwtFilter.authenticate()`(인증 성공 시점에 직접 `MDC.put`)로 이관해 해소. 수정 후 `AuthIntegrationTest --rerun`과 실제 `bootRun` 양쪽에서 `JwtIssuer`의 DEBUG 로그에 `traceId`가 붙는 것을 재확인
    - Minor 6건 중 3건 추가 반영: `logback-spring.xml`의 Boot 기본 로거 include 누락(위에서 해결), `MetricsIntegrationTest`의 사실상 항상-참에 가까운 약한 assertion을 `allfolio_simulation_duration_seconds_bucket{le="` 단일 패턴으로 강화, `userId`가 MDC에 채워지는지 검증하는 자동 테스트 부재 — `MdcFilterIntegrationTest` 신규(2케이스: 인증된 요청의 `userId`가 JWT subject와 일치, 연속 요청의 `traceId`가 서로 다름)로 해소. 나머지 2건은 보류 — 상세는 아래 「남은 갭」
  - ✅ 2차 code-reviewer 독립 검증(1차 수정분을 처음부터 다시 독립적으로 재검증, `./gradlew test --rerun-tasks` 71개 테스트 전체 통과 + 실제 `bootRun` 재실측 포함) — Blocker 0건, Major 0건. Minor 6건 발견, 이번에 3건 반영:
    - **(수정 완료)** 위에서 추가한 `MdcFilterIntegrationTest`의 2케이스는 사실 M-1 회귀를 못 잡는다는 지적 — 뮤테이션 검증(`MdcFilter`의 `@Order`를 실제로 지워봄)으로 실증: 컨트롤러 시점엔 필터 순서가 뒤바뀌어도 이미 `traceId`/`userId`가 채워진 뒤라 두 테스트 모두 그대로 통과했다. `securityChainLogsCarryTraceId` 테스트를 신규 추가 — `JwtIssuer` 로거에 `ListAppender`를 직접 붙여 시큐리티 체인 *내부*(파싱 실패 등 진단 로그)의 `traceId` 존재를 검증한다. 같은 뮤테이션(`@Order` 제거)으로 이번엔 이 테스트가 실제로 실패하는 것을 확인해 검출력을 실증한 뒤 `@Order`를 원복
    - **(수정 완료)** `AuthService.signup()`이 `userRepository.save()` 직후 곧바로 "회원가입 성공" 로그를 남기는데, `uk_users_email` 경합(동시에 같은 이메일로 가입 시도) 시 INSERT는 트랜잭션 커밋 시점에야 실패할 수 있어, 실제로는 실패한 회원가입이 감사 로그엔 "성공"으로 남을 수 있는 경로였다. `TransactionSynchronizationManager.registerSynchronization`의 `afterCommit()` 콜백으로 로그 시점을 커밋 이후로 이관해 해소(같은 요청 스레드에서 동기 실행되므로 `traceId` MDC는 그대로 유지됨, 실측 확인). 중복 이메일 재현(같은 이메일로 2회 연속 가입 시도 → 첫 번째만 201+AUDIT 로그, 두 번째는 409+로그 없음)으로 정상 동작 확인
    - **(수정 완료)** `logback-spring.xml`의 `${LOGGING_LEVEL_ROOT:-INFO}` 폴백이 실효 없는 죽은 설정이라는 지적 — 위 항목에서 리터럴 `INFO`로 되돌림
    - Minor 3건은 보류: 히스토그램 5ms 경계 보간 이슈(위 항목에 서술, Task 015 착수 시 판단), 로그인 실패 로그 주체 미기록(「남은 갭」), `userId` MDC 키가 실제 프로덕션 로그엔 아직 한 번도 안 찍힘(인증 성공 후 경로에 로그 문장이 아직 없을 뿐 결함 아님 — 정보성)
  - ⚠️ 남은 갭:
    - 로그인 실패 `AUDIT` 로그가 어떤 계정을 대상으로 한 실패인지 남기지 않는다(user enumeration 방지는 HTTP 응답 계약에 대한 요구이지 서버 내부 로그에 대한 요구는 아니므로 스펙 위반은 아님, code-reviewer도 판단 사항으로만 제시) — 브루트포스 탐지 필요성이 커지면 비밀번호 불일치 경로에 한해 `userId` 기록을 다음 착수 시 재검토
    - 현재는 동기 필터 체인만 존재해(`grep`으로 `CompletableFuture`/`@Async`/`SseEmitter` 0건 확인) Virtual Thread 환경에서 MDC 누수가 없다. Phase 4 Task 025(SSE)에서 MVC 비동기 처리가 들어오면 `OncePerRequestFilter`가 비동기 디스패치 시작 시점에 체인을 빠져나가며 MDC를 지우므로 emitter 콜백에는 `traceId`가 없다 — 지금 고칠 사안은 아니고 Task 025 착수 시 재검토
    - `MdcFilter`는 `OncePerRequestFilter`라 컨테이너 ERROR 디스패치(핸들러 없는 404 등)에는 기본적으로 재실행되지 않아 `traceId`가 비어있다 — `SecurityConfig`가 "보안 필터는 ERROR 디스패치에도 적용된다"고 명시한 것과는 비대칭. 실제로는 `GlobalExceptionHandler`가 대부분의 4xx/5xx를 REQUEST 디스패치 중에 처리해 `traceId`가 붙으므로 영향은 미미(2차 code-reviewer 확인) — 지금 고칠 사안은 아님
    - Task 015(시뮬레이터) 구현 시 `allfolio.simulation.duration` 이름으로 실제 `Timer`를 기록하는 프로덕션 코드(`@Timed` 또는 `Timer.record`)가 필요하다 — 이번엔 `MeterRegistry` 직접 호출로 설정이 살아있음만 검증했다

- **Task 015: 물타기 시뮬레이터 구현 (F006)** ✅ — 완료 (2026-08-25)
  - ✅ `POST /v1/simulate/avg-price` 신규 구현 — `domain/service/SimulationService.java` + `web/SimulateController.java` 2계층(`AssetController`/`PortfolioController`와 동일한 컨트롤러 패턴). 요청·응답 DTO는 Task 006에서 이미 확정돼 있어 변경 없음
  - ✅ 소유권 검증은 `AssetService`와 동일하게 `findByIdAndUser_Id` → 없거나 타 유저 소유면 `AssetNotFoundException` → 기존 404 `ASSET_NOT_FOUND` 재사용(신규 에러 코드 없음, 403 대신 404로 ID 유출 방지)
  - ✅ In-Memory 가중평균 계산, DB 쓰기 없음(`@Transactional(readOnly = true)`, `save`/`flush`/`delete` 호출 0건 — 2차 code-reviewer가 grep으로 실측 확인). `BigDecimal.divide(분모, scale, HALF_UP)`로 나눗셈·반올림을 1회에 처리. scale 결정 로직(`COIN` 8자리, `KRW` 0/`USD` 4/기타 2)은 `PortfolioService.scaleFor`/`scaleForCurrency`를 로컬 복제(2차 code-reviewer가 두 메서드 본문을 문자열로 직접 비교해 완전히 동일함을 확인) — 공유 유틸 추출은 Task 016에서 완료(아래 참고)
  - ✅ `currentAvgPrice`/`expectedAvgPrice` 둘 다 응답 직전 scale 재정규화 — `SimulateAvgPriceResponse.of()`가 `expectedQuantity`만 8자리로 반올림하고 두 평단가는 호출자 책임이라, 누락하면 DB `NUMERIC(28,8)` 왕복 scale이 그대로 노출되는 결함(Task 012/013 전례)이 재현될 뻔했으나 처음부터 반영돼 있었음(문자열 정확 비교 골든 테스트로 검증)
  - ✅ CASH 자산도 별도 거부 로직 없이 그냥 계산해서 반환(사용자 확정 결정, 계획 단계에서 확인) — 프론트가 이미 UI로 CASH 자산에는 시뮬레이터를 안 보여주므로 백엔드가 중복 방어할 근거가 약하다고 판단
  - ✅ `allfolio.simulation.duration` Micrometer 타이머 계측(Task 014가 준비해둔 히스토그램 설정에 실제 `Timer.record()` 연결) — `Timer.start()`는 조회 직전, `sample.stop()`은 계산 성공 시에만 호출해 실패(자산 못 찾음) 경로는 미계측(의도된 설계, 2차 code-reviewer가 Javadoc과 코드 위치로 확인 — Timer.Sample은 stop() 전엔 레지스트리에 아무것도 등록 안 해 누수 아님)
  - ✅ `SimulateIntegrationTest` 신규(Testcontainers, 10케이스) — 골든 케이스(60,000원×10주+55,000원×5주→58,333원, `expectedQuantity` "15.00000000") 문자열 정확 비교, 타 유저 자산 접근 404, 존재하지 않는 자산 404, 미인증 401(에러 코드까지 단언), DB 미변경(`HoldingRepository` 재조회로 quantity/avgPrice/**version**/updatedAt 전부 동일 확인 — version까지 봐서 UPDATE가 안 나갔음을 실증), CASH 자산 정상 동작, COIN 8자리 HALF_UP 반올림(무한소수 케이스), USD 4자리 스케일, `additionalQuantity=0` → 400 `VALIDATION_ERROR`, 메트릭 카운트 자동 증가 검증
  - ✅ `SimulationPerformanceTest` 신규 — `MockMvc`/HTTP 스택 없이 `SimulationService`를 직접 호출(ROADMAP KPI 정의가 "holding 단건 조회 포함한 수치"일 뿐 HTTP 프레이밍까지 포함하지 않는다고 해석), 워밍업 200회 + 측정 1,000회로 P99 계산. **실측 P99 = 약 1.0~1.4ms**(여러 차례 재실행에서 일관됨), 5ms 기준 대비 3~5배 여유. 2차 code-reviewer가 2차 캐시 미설정(매 반복 실제 SELECT 발생)을 확인해 "가짜 측정"이 아님을 검증
  - ✅ 1차 실제 기동 검증(curl) + code-reviewer 독립 검증(Blocker 0건, Major 0건). Minor 7건 중 4건 반영: 검증 실패(`additionalQuantity=0`) 테스트 추가, 401 응답의 `code` 단언 보강, 메트릭 카운트 자동 증가 단언 추가, USD scale 4 골든 케이스 추가(153.7448) — 전부 `SimulateIntegrationTest`에 반영, 7→10케이스로 증가
  - ⚠️ 남은 갭:
    - ✅ (Task 016에서 해소) `scaleFor`/`scaleForCurrency`가 `PortfolioService`/`SimulationService` 2곳에 완전 동일하게 복제돼 있던 문제 — `domain/PrecisionScale` 공유 유틸로 통합, 두 서비스 모두 이 유틸을 호출하도록 리팩터링(기존 응답 문자열 무변경, 회귀 테스트로 확인)
    - 프론트(`frontend/src/lib/simulate.ts`)의 `simulateAvgPrice`는 `currentAvgPrice`를 입력값 그대로 통과시킨다 — 서버 응답의 scale 정규화와 표기가 갈릴 가능성이 있음, Task 018(프론트-백엔드 실연동) 착수 시 확인 필요

- **Task 016: 금융 정밀도 및 도메인 통합 테스트** ✅ — 완료 (2026-08-27)
  - 원안 체크리스트(`BigDecimalPrecisionTest`/`SimulationServiceTest`/`AssetCrudIntegrationTest`/`OptimisticLockingTest`)는 Phase 1 Step 6 시절 작성돼, 그 사이 Task 012·013·015가 이미 만든 자체 통합 테스트(`AssetIntegrationTest` 15케이스·`PortfolioIntegrationTest`·`SimulateIntegrationTest`)와 상당 부분 겹쳤다. 실제 코드베이스 조사로 남은 갭만 추려 아래처럼 조정해 구현했다
  - ✅ `domain/PrecisionScale.java` 신규 — `PortfolioService`/`SimulationService`에 완전히 동일하게 복제돼 있던 `scaleFor`/`scaleForCurrency`(Task 013·015가 이월한 항목)를 정적 유틸 하나로 통합. 두 서비스는 이 유틸을 호출만 하도록 리팩터링(로직 변경 없이 코드만 이동), quantity 공용 스케일(8)도 `PrecisionScale.QUANTITY_SCALE` 상수로 뽑아 `SimulateAvgPriceResponse`·`PortfolioService`가 함께 참조하도록 정리. 리팩터링 전후로 `PortfolioIntegrationTest`·`SimulateIntegrationTest`의 문자열 정확 비교 골든 테스트가 전부 변경 없이 통과해 응답 문자열이 한 글자도 안 바뀌었음을 확인
  - ✅ `BigDecimalPrecisionTest` 신규 — `PrecisionScale.scaleFor`를 「금융 정밀도 규칙」 표(아래) 전 조합(STOCK/CASH/COIN × KRW/USD/기타 통화 폴백)으로 파라미터라이즈드 검증 + HALF_UP 반올림 경계값 2건. 같은 클래스에 `double`/`float` 금지 규칙(CLAUDE.md)을 `src/main/java` 전체 스캔으로 자동 검사하는 테스트 추가 — 처음엔 라인 단위로 `//`만 잘라내 여러 줄 Javadoc/블록 주석에 "double" 단어가 있으면 오탐하는 결함이 있었으나(code-reviewer 지적), 상태 추적형 주석 제거 로직으로 교체해 해소(뮤테이션 검증으로 오탐 해소·실제 위반 검출 둘 다 확인)
  - ✅ `OptimisticLockingTest` 신설 — `AssetService.updateHolding()`의 낙관적 잠금이 두 요청을 동시에 제출해도 정확히 1건만 성공(200)시키고 1건은 409 `HOLDING_CONFLICT`를 내는지 `ExecutorService`(2스레드)+`CountDownLatch`로 검증. 기존 `AssetIntegrationTest.updateHoldingWithStaleVersionReturnsHoldingConflict`는 순차 재현이라 실제 동시 제출 경로는 검증한 적이 없었다. `--rerun` 5회 연속 통과로 flaky 아님을 확인. **주의(code-reviewer 지적, 클래스 Javadoc에 명시)**: `AssetService.updateHolding()`이 flush 이전에 인메모리로 버전을 먼저 비교하므로, 이 테스트는 "동시 제출 시 정확히 1건만 성공"까지만 증명하고 "DB 트랜잭션이 실제로 겹쳤는지"는 증명하지 않는다
  - ✅ `SimulationServiceTest` 신설 — Mockito(`AssetRepository`/`HoldingRepository` 목킹) + 실제 `SimpleMeterRegistry`로 Spring 컨텍스트 없이 `SimulationService.simulate()`의 계산 로직만 단위 테스트(골든 케이스·HALF_UP 경계값·CASH·COIN 8자리 반올림·자산 미소유 시 예외·실패 경로 미계측 확인 6케이스). 초기 버전은 입력 `avgPrice`가 이미 목표 scale이라 `currentAvgPrice.setScale(...)`를 지워도 통과하는 뮤테이션 사각지대가 있었으나(code-reviewer 지적), 목표 scale보다 자리수가 많은 입력(`60000.4`) 케이스를 추가해 해소
  - ✅ 원안의 `AssetCrudIntegrationTest`는 기존 `AssetIntegrationTest`(15케이스)와 완전 중복이라 신규 클래스를 만들지 않고, 대신 이 클래스에 정밀도 특화 케이스 3건을 추가했다: NUMERIC(28,8) 상한 근처 값(정수부 20자리+소수부 8자리) CRUD 왕복 무손실 확인, `@Digits(integer=20)` 상한 초과(정수부 21자리) 시 400 `VALIDATION_ERROR` 확인, `POST` 응답(요청 스케일 그대로)과 `GET /v1/assets` 응답(DB 왕복 스케일 8 고정)의 표기 차이를 "미결 사항의 현재 동작 스냅샷"으로 고정하는 회귀 테스트(위 Task 012·013 「남은 갭」이 여전히 미정임을 정확히 반영하는 문구로 작성)
  - ✅ `grep -rn "double \|float " src/main/java --include="*.java"` 0건 실측 확인, `BigDecimalPrecisionTest`의 자동 검사로 회귀 방지
  - ✅ code-reviewer 독립 검증(실제 `./gradlew test --rerun-tasks` 실행 + 뮤테이션 검증 포함) — Blocker 0건, Major 0건. Minor 9건 발견, 위 서술된 5건(주석 오탐·동시성 Javadoc·계산 뮤테이션 사각지대·quantity 상수화·상한 초과 케이스·문구 정정)은 전부 반영. 나머지는 이 항목과 아래 「금융 정밀도 규칙」 갱신으로 해소
  - ✅ 테스트 개수 실측: 기존 85케이스 → **108케이스**(`BigDecimalPrecisionTest` 13, `OptimisticLockingTest` 1, `SimulationServiceTest` 6, `AssetIntegrationTest` +3 신규 반영)
  - ⚠️ 남은 갭:
    - `GET /v1/assets`와 `GET /v1/portfolio`의 `quantity`/`avgPrice` 스케일 표기 불일치(Task 012·013 이월)는 이번에 회귀 테스트로 스냅샷만 고정했을 뿐, 통일 여부는 여전히 미정 — 다음 착수 시 결정
    - `OptimisticLockingTest`가 진짜 DB 트랜잭션 경합까지 구분해 증명하지는 못한다(위 서술) — 필요해지면 예외 발생 지점을 로거로 구분하는 방식 검토

- **Task 017: MVP 로컬 실행 문서화** ✅ — 완료 (2026-08-27)
  - ✅ 저장소 루트에 `README.md` 신규 작성 — 사전 요구사항, 백엔드 기동(`docker compose up -d` → `ALLFOLIO_JWT_SECRET` 환경변수 필수 이유 → `gradlew bootRun` → `/actuator/health` 확인), 프론트 기동(`npm run dev`, `/v1` 프록시 설명, 실데이터 연동은 Task 018 예정임을 명시), curl 기반 API 흐름(회원가입→로그인→자산 등록(STOCK/CASH)→목록 조회→물타기 시뮬레이션→포트폴리오 조회) 순서로 구성. CLAUDE.md(빌드/테스트 상세·아키텍처 레퍼런스)와 역할을 분리해 README는 "처음 실행 온보딩" 전용 문서로 유지
  - ✅ 실제 `docker compose up -d` + `ALLFOLIO_JWT_SECRET` 설정 + `gradlew bootRun`으로 기동해 README의 curl 시퀀스를 처음부터 끝까지 그대로 실행·검증. 이 과정에서 실제 응답을 확인해 예시를 맞춤: (a) 자산 생성 직후 응답은 `quantity`/`avgPrice`가 패딩 없이 내려오지만 `GET /v1/assets` 목록 조회에서는 소수점 8자리로 패딩됨(Task 012·013·016이 이미 스냅샷 고정해둔 것과 동일한 표기 차이), (b) KRW 통화 금액은 `PrecisionScale` 규칙상 소수점 0자리라 시뮬레이션 결과(`expectedAvgPrice`)가 정수로 나옴 — 반올림 오류가 아니라 의도된 설계
  - ✅ `null`로 내려오는 필드(`evaluationKrw`/`unrealizedPnl`/`weight`/`currentWeight`/`expectedWeight`/`totalEvaluationKrw`/`totalUnrealizedPnl`)가 Task 023(Phase 3) 전까지 의도된 설계임을 README에 명시, 에러 응답 포맷(`{code,message,timestamp}`)과 대표 코드 표도 포함
  - 코드·설정 파일은 변경하지 않은 순수 문서화 Task(검증에 쓴 `bootRun` 프로세스는 종료해 8080 포트 정리)

### Phase 3: 핵심 기능 구현 (실데이터 연동 및 외부 시세)

- **Task 018: 프론트–백엔드 실데이터 연동** ✅ — 완료 (2026-08-30)
  - ✅ `frontend/src/api/assetApi.ts` 신규 — `getAsset`/`createAsset`/`updateHolding`/`deleteAsset`/`getPortfolio`/`simulateAvgPrice` 6개 함수. 공통 헬퍼 `authorizedRequest()`가 `tokenStorage.getToken()`으로 얻은 토큰을 `Authorization: Bearer` 헤더에 자동 주입, 실패 응답은 `authApi.ts`의 `ApiError(code, message)`를 재사용(신규 에러 클래스 없음). `GET /v1/assets`(커서 페이지네이션 목록)를 이번 3개 화면 어디도 쓰지 않아 `listAssets()`는 구현하지 않음(CLAUDE.md 「Simplicity First」) — 전용 단위 테스트 파일도 `authApi.ts`와 동일하게 두지 않고, 호출 화면의 fetch 모킹 테스트가 간접 검증하는 기존 컨벤션을 따름
  - ✅ `PortfolioPage.tsx` — `portfolioFixture` 참조를 `getPortfolio()` 실 호출로 교체. 로딩/에러/완료 3상태를 유니온 타입으로 관리하고, flash 배너는 데이터 상태와 무관하게 항상 최상단에 렌더(등록 직후 목록이 로딩 중이어도 안내는 바로 보여야 함). 401이면 `auth.logout()` 후 `/login` 리다이렉트(`state.from` 보존)
  - ✅ `AssetNewPage.tsx` — `'void request;'` 플레이스홀더를 `createAsset()` 실 호출로 교체, `LoginPage.tsx`의 `submitting`/`submitError` 패턴 재사용
  - ✅ `AssetDetailPage.tsx`(가장 복잡한 화면) — `useEffect([id])`에서 `Promise.allSettled([getAsset(id), getPortfolio()])`로 병렬 조회. `Promise.all`이 아닌 `allSettled`를 쓴 이유: `getPortfolio()`만 실패해도 `getAsset()`이 성공하면 화면은 정상 렌더돼야 한다는 기존 "portfolioItem 부재 회귀" 원칙과 fail-fast가 충돌하기 때문. `getAsset` 실패는 코드별 분기(`UNAUTHORIZED`→로그아웃+이동, `ASSET_NOT_FOUND`→not-found 렌더, 그 외→에러 상태). 조회 성공 시 `editQuantity`/`editAvgPrice`를 응답 값으로 초기화(Task 011 남은 갭 해소). 물타기 시뮬레이터는 로컬 `lib/simulate.ts` 계산 대신 `assetApi.simulateAvgPrice()` 실 호출로 전환, `additionalQuantity > 0` 검증을 `lib/validation.ts`의 신규 `validateAdditionalQuantity()`로 분리(Task 011 남은 갭 해소) — 이에 따라 도달 불가능해진 총수량 0 가드와 전용 회귀 테스트를 제거. 수정 폼은 `updateHolding()` 연동, `409 HOLDING_CONFLICT`는 전용 분기 없이 기존 `messageForErrorCode()` 범용 조회로 자연 처리. 삭제는 `deleteAsset()` 연동 + 연타 방지(`deleteSubmitting` state, `ConfirmDialog`에 `confirmDisabled` prop 신설)
  - ✅ `/dev/ui` 라우트·`DevUiPage.tsx` 삭제, 이어서 도달 불가능해진 `lib/simulate.ts`·`lib/simulate.test.ts`·`api/fixtures.ts` 삭제(CLAUDE.md 「Surgical Changes」 — 이번 변경이 직접 만든 죽은 코드이므로 삭제 대상으로 분류)
  - ✅ 3개 화면 `*.test.tsx` 전부 `vi.doMock('../api/fixtures', ...)`에서 `vi.stubGlobal('fetch', fetchMock)` 방식으로 재작성(`LoginPage.test.tsx` 선례). `renderXxxPage()` 테스트 헬퍼에 `AuthProvider` 래핑을 신규 추가(3개 화면 모두 이번에 처음 `useAuth()`를 호출하게 되는데 누락돼 있었음 — 실측으로 발견). 테스트 개수: 136(Task 002~017 누적) → **141**(Task 018 최종, 순증가 +5)
  - ✅ 구현 과정에서 에이전트의 자체 실행 결과를 그대로 신뢰하지 않고 매 하위 태스크마다 `tsc`/`oxlint`/`vitest`/`vite build`를 직접 재실행해 검증 — 실제 결함 1건 발견·수정: `QUANTITY_NOT_POSITIVE`를 `lib/messages.ts`와 사용 코드에는 추가했지만 `lib/validation.ts`의 `ValidationCode` 유니온에는 빠뜨려 `tsc`가 타입 오류 3건(TS2353/TS2339/TS2322)을 냄 — 유니온에 추가해 해소
  - ✅ code-reviewer 에이전트 독립 검증(뮤테이션 테스트 10건 포함, 격리된 사본에서 실행) — Blocker 0건, Major 0건. Minor 7건 발견, 6건 반영:
    - **(수정 완료)** `Authorization` 헤더·`auth.logout()` 호출·`PUT` 메서드·`version` 필드·204 분기를 지워도 테스트가 못 잡던 문제 — `fetchMock.mock.calls`로 실제 요청의 method/path/headers/body를 직접 단언하는 테스트를 추가하고, 뮤테이션(코드를 실제로 지웠다가 테스트 실패 확인 후 원복)으로 검출력 실증
    - **(수정 완료)** `getPortfolio()` 실패 시 취득원가(`cost`)가 "—"로 빠지는데 화면 문구는 "시세 연동 전"이라는 다른 원인을 말하던 문제 — 조회 상태에 `portfolioFetchFailed` 플래그를 추가해 원인을 구분 표시
    - **(수정 완료, 최초 판단은 반대로 뒤집힘)** `// eslint-disable-next-line react-hooks/exhaustive-deps` 억제 주석이 아무 규칙도 안 끈다는 code-reviewer의 최초 판단(`.oxlintrc.json`에 `react-hooks` 플러그인 미설정이 근거)에 따라 주석을 제거했더니, 실측 결과 oxlint가 이 규칙을 기본 내장으로 인식해 경고 2건이 새로 나타남 — code-reviewer의 최초 판단이 틀렸음이 재실측으로 드러나 주석을 원상복구(Task 010의 "근거가 부정확했던 억제 주석을 제거"한 선례와 반대 방향 사례 — 이번엔 억제가 정당했음)
    - **(수정 완료)** `QUANTITY_NOT_POSITIVE` 판정 로직이 `lib/validation.ts` 밖(`AssetDetailPage.tsx` 안)에 있어 그 파일 자신의 "검증 로직은 이 파일에만" 원칙과 어긋나던 문제 — `validateAdditionalQuantity()` 함수를 신설해 페이지가 이 함수 하나만 호출하도록 정리
    - **(수정 완료)** 삭제 확인 다이얼로그의 confirm 버튼에 연타 방지가 없어 `DELETE` 중복 전송 가능성 — `ConfirmDialog`에 `confirmDisabled` prop 신설
    - **(수정 완료)** `docs/DESIGN.md`·`docs/ROADMAP.md`에 삭제된 `DevUiPage.tsx`/`fixtures.ts`를 가리키던 잔여 참조 3곳(「개발 단계」 절 밖이라 놓치기 쉬웠음)을 현재 유효한 참조로 교체
    - **(보류)** 비-JSON 에러 응답(예: 프록시가 내는 HTML 502) 시 `authorizedRequest`의 `res.json()`이 `SyntaxError`를 던져 `ApiError`가 아닌 예외가 새는 경로 — 화면은 `NETWORK_ERROR` 폴백으로 우아하게 저하돼 크래시는 아니고 발생 가능성도 낮아 이번엔 보류
  - ⚠️ 남은 갭:
    - `GET /v1/assets`(커서 페이지네이션 목록) API를 소비하는 화면이 아직 없다 — `assetApi.ts`에 `listAssets()`를 만들지 않았으므로, 이 목록이 필요한 화면(예: Task 010 후속 과제인 티커 검색 자동완성)이 생기면 그때 추가한다
    - ✅ (해소) Task 019에서 401 발생 시 자동 갱신(refresh-and-retry)이 추가돼 재로그인 없이 세션이 유지된다
    - 비-JSON 에러 응답 처리(위 Minor 보류 항목)는 발생 가능성이 낮아 미루되, 프록시/게이트웨이 계층이 생기는 Task 027(Capacitor) 이후 재검토

- **Task 019: 인증 강화 — Refresh Token 및 로그아웃 (F010)** ✅ — 완료 (2026-08-30)
  - Task 003의 남은 갭 해소. 실시간 차트(F007)를 띄워두는 사용 패턴과 Access Token 15분 만료가 충돌하는 문제를, 재로그인 없이 자동 갱신하는 흐름으로 해결한다.
  - **핵심 설계 결정**: Refresh Token(장기 세션 유지 토큰)은 Access Token과 달리 무상태 JWT로 만들지 않았다. 로그아웃(F010 요구 기능)은 "서버가 특정 토큰을 더 이상 유효하지 않다고 판단"하는 기능인데, 무상태 JWT는 서버가 개별 토큰을 무효화할 방법이 없기 때문이다. 대신 `SecureRandom` 32바이트로 만든 무작위 원문 토큰을 클라이언트에 발급하고, DB(`refresh_tokens` 테이블)에는 SHA-256 해시(64자 hex)만 저장한다. Access Token은 기존 그대로 무상태 HS256 JWT 유지(요청마다 DB 조회 없음, 기존 성능 특성 보존)
  - ✅ `V3__refresh_tokens.sql` 신규(`id`/`user_id` FK `ON DELETE CASCADE`/`token_hash` UNIQUE/`expires_at`/`revoked_at`/`created_at`) — `version`(낙관적 잠금) 컬럼은 의도적으로 생략(토큰 문자열로 단건 조회 후 즉시 revoke하는 패턴이라 동시 수정 충돌 시나리오가 없음). `RefreshToken` 엔티티(`revoke()` 멱등 캡슐화), `RefreshTokenRepository`
  - ✅ `RefreshTokenIssuer`(`infra/security/`) — 원문 발급(`issue()`), SHA-256 해시(`hash()`, BCrypt 대신 채택 — 이미 고엔트로피 무작위값이라 사전 대입 공격 대상이 아니므로 느린 해시가 불필요), TTL 반영 만료시각 계산(`expiresAt()`). `application.yml`에 `allfolio.jwt.refresh-token-ttl: 14d` 추가
  - ✅ `AuthService.refresh()`(rotation — 갱신마다 기존 토큰 즉시 폐기 + 새 Access+Refresh 쌍 발급, 탈취된 토큰 재사용 방지) / `logout()`(idempotent — 존재하지 않거나 이미 폐기된 토큰으로 호출해도 예외 없이 204, 정보 비노출 원칙은 `ASSET_NOT_FOUND`가 403 대신 404를 쓰는 것과 동일). `POST /v1/auth/refresh`(200/401)·`POST /v1/auth/logout`(204) 신규, 둘 다 Access Token 인증 없이 호출 가능(Refresh Token 소유 자체가 세션 종료·갱신 권한의 증명) — `SecurityConfig` 화이트리스트에 명시 추가. 신규 에러 코드 `INVALID_REFRESH_TOKEN`(401)
  - ✅ 프론트 `assetApi.ts`의 `authorizedRequest()`에 401 자동 refresh-and-retry 추가 — 실제 요청(`doFetch`)과 재시도 판단(`authorizedRequest`)을 분리해 재귀적 무한 재시도가 구조적으로 불가능하게 설계, 동시에 여러 요청이 401을 맞아도 갱신 요청은 모듈 스코프 공유 Promise로 1회만 나가도록 가드(rotation 특성상 두 번째 갱신 시도는 반드시 실패하므로 필수). 기존 4개 화면의 `err.code === 'UNAUTHORIZED'` → `auth.logout()` 처리는 코드 변경 없이 "갱신까지 실패한 뒤의 최종 폴백"으로 의미만 자연스럽게 바뀜
  - ✅ 기존에 이미 있던 `AppLayout.tsx` 로그아웃 버튼(신규 UI 아님)이 서버 `POST /v1/auth/logout` 호출로 Refresh Token을 폐기한 뒤 로컬 정리하도록 확장 — 서버 호출이 실패해도(네트워크 오류 등) 로컬 정리는 항상 진행해 로그인 상태에 갇히지 않게 함
  - ✅ 테스트 개수: 백엔드 `AuthIntegrationTest` 24 → **31**(+7: 갱신 성공·회전 후 재사용 차단·만료 차단·로그아웃 후 재사용 차단·로그아웃 멱등성 등), `SchemaMigrationTest` 12 → **17**(+5: refresh_tokens UNIQUE·CASCADE·nullable·인덱스·version 부재 검증), `RefreshTokenIssuerTest` 신규 4건, 백엔드 전체 **124개**(0 실패). 프론트 141(Task 018 기준) → **149**(+8: `assetApi.test.ts` 5건·`AppLayout.test.tsx` 3건 신규)
  - ✅ `ApiContractSerializationTest`(Task 006)는 애초에 `TokenResponse`를 다루지 않아 갱신 대상이 아니었음을 직접 확인 — 계획 단계에서 "갱신 필요"로 잘못 추정했던 항목을 실측으로 정정
  - ✅ code-reviewer 에이전트 독립 검증 — Blocker 0건. Major 2건(신규 에러 코드·엔드포인트가 이 문서에 미등재 — 이번 갱신으로 해소), Minor 6건 발견, 4건 반영:
    - **(수정 완료)** 안 쓰이는 `RefreshTokenRepository.findByUser_Id()` 삭제 — CLAUDE.md 「Simplicity First」(요청받지 않은 기능 금지) 위반으로 판단, 필요해지면 그 기능(전 기기 로그아웃) 착수 시 다시 추가
    - **(수정 완료)** `AuthService.login()`이 Refresh Token INSERT로 인해 이제 커밋 실패 가능성이 생겼는데 감사 로그가 커밋 전에 남던 문제 — `signup()`이 이미 쓰던 "커밋 성공 후에만 로그" 패턴(`TransactionSynchronization.afterCommit`)을 동일 적용
    - **(수정 완료)** `refresh()`/`logout()`에 감사 로그가 전혀 없던 공백 — 성공 시(`afterCommit`)와 실패 시(폐기·만료된 토큰 재사용은 탈취 탐지의 유일한 관측 신호이므로 `userId` 포함 warn 로그) 추가, 원문 토큰은 어떤 로그에도 남기지 않음을 직접 확인
    - **(수정 완료)** 401 자동 재시도의 "최대 1회" 안전장치를 검증하는 테스트가 없던 공백 — 뮤테이션 테스트(재시도 코드를 실제로 무한재시도로 바꿔 테스트가 행(hang)에 빠지는지 확인 후 원복)로 검출력을 실증하는 신규 케이스 추가
    - **(보류)** 갱신 시 React `AuthContext`의 `token` state가 silent refresh와 동기화되지 않음 — 현재는 `RequireAuth`/`AppLayout` 모두 truthiness로만 소비해 무해하지만, 향후 `auth.token` 값 자체를 Authorization 헤더 등에 쓰는 화면이 생기면 만료된 값이 나갈 수 있음. 지금 고칠 필요는 없어 남은 갭으로만 기록
    - **(보류)** 갱신 실패로 확정된 Refresh Token이 `localStorage`에서 지워지지 않음 — 현재는 호출 화면들이 `UNAUTHORIZED` 분기에서 `auth.logout()`으로 결과적으로 정리되어 실질 영향 없음
  - ⚠️ 남은 갭:
    - 만료된 `refresh_tokens` 행을 지우는 정리 배치 없음 — `token_hash` UNIQUE 인덱스 조회라 성능 영향은 없으나 저장 공간은 계속 누적됨
    - Rotation된(이미 폐기된) 토큰이 재사용될 때 해당 유저의 다른 세션 전체를 강제 로그아웃(cascade revoke)하는 기능 없음 — 다중 기기 동시 로그인 요구사항이 없어 의도적으로 축소
    - **다중 탭 환경에서 한쪽 탭만 예기치 않게 로그아웃될 수 있음(신규 발견)**: 401 자동 재시도의 동시성 가드는 같은 탭(같은 JS 모듈 인스턴스) 안에서만 유효하다. 두 탭이 거의 동시에 Access Token 만료를 맞으면 각자 독립적으로 갱신을 시도하는데, rotation 특성상 먼저 도착한 탭만 성공하고 늦게 도착한 탭은 이미 폐기된 토큰으로 시도한 셈이 되어 강제 로그아웃된다. 다중 탭 동시 사용은 PRD·ROADMAP 어디에도 요구사항으로 명시된 적이 없고, 고치려면 `BroadcastChannel`/`storage` 이벤트로 탭 간 토큰 상태를 동기화하는 별도 구현이 필요해 이번 범위를 크게 벗어난다
    - 위 code-reviewer 보류 항목 2건(React state 미동기화, 실패한 Refresh Token 미정리)
    - `README.md`의 curl 예시·인증 방식 설명이 아직 Task 003 시점(Access Token만) 기준 — Task 019 갱신은 이번 범위 밖

- **Task 020: E2E 통합 테스트 (Playwright MCP)** ✅ — 완료 (2026-08-31)
  - 상세 시나리오·실행 절차·결과 근거는 [`docs/E2E_SCENARIOS.md`](E2E_SCENARIOS.md) 참고(신규 4번째 문서, PRD/ROADMAP/DESIGN 체계에 이어 추가). npm `@playwright/test`가 아니라 이미 연결된 Playwright MCP 서버를 에이전트가 직접 조작해 실제 브라우저+백엔드+DB가 연결된 상태에서 검증하는 방식
  - ✅ 신규 `qa-e2e` 서브에이전트(`.claude/agents/qa-e2e.md`) 신설 — ui-ux-designer.md·senior-frontend.md에 이미 못박혀 있던 "별도 QA 에이전트" 역할. 코드는 직접 수정하지 않고 버그 발견 시 담당 에이전트(senior-frontend/senior-backend/database)로 라우팅만 한다
  - ✅ `shrimp-rules.md`를 Task 007/012 시점에서 Task 019까지 전체 최신화(Agent Routing에 qa-e2e 추가, Project Phase Status·API 엔드포인트 표·에러 코드 목록 갱신)
  - ✅ 해피 패스 7개(회원가입→로그인/로그아웃→STOCK/CASH 등록→시뮬레이터→보유수정→삭제) + 에러·엣지 케이스 9개(중복 이메일 409·로그인 실패 401·비인증 리다이렉트·존재하지 않는 자산 404·타 유저 자산 404(403 아님 확정)·시뮬레이터 잘못된 입력 차단·삭제 연타 방지·낙관적 잠금 충돌 409·401 자동 refresh-and-retry) 총 **16개 시나리오 전부 PASS**(FAIL 0건)
  - ✅ 재현 난이도가 있던 두 케이스도 실측 완료: 낙관적 잠금 충돌은 브라우저 탭 2개(`browser_tabs`)로 동시 수정을 재현해 탭A 200(`version` 0→1)·탭B 409 `HOLDING_CONFLICT` 확인. 401 자동갱신은 Access Token TTL(15분) 설정을 건드리지 않고 실제로 16분을 대기해 401→`POST /v1/auth/refresh` 200(토큰 rotation 확인)→재시도 200 순서와 세션 미종료를 확인
  - ✅ 코드 버그 0건 발견. 부수 발견 2건은 버그가 아닌 기존 설계 확인 사항(CASH 자산에는 물타기 시뮬레이터 UI 자체가 없음 — 평단가 개념 부재로 당연한 설계, 401 직후 콘솔에 표준 fetch 에러 로그가 남는 것은 화면 동작과 무관)으로 `docs/E2E_SCENARIOS.md`에 기록
  - ⚠️ 남은 갭:
    - 이번 실행은 에이전트가 Playwright MCP로 수동 재현하는 방식이라 CI 자동 재실행 구조가 아니다 — 반복 회귀 검증이 필요해지면 headless 자동화(`@playwright/test` 등) 도입 여부를 별도로 검토해야 한다
    - 테스트 계정(`e2e-happy-*`, `e2e-userb-*`)과 등록한 자산이 로컬 dev DB에 남아있음(실질 영향 없어 정리 보류)

- **Task 021: 외부 시세 API 연동** ✅ — 완료 (2026-09-01)
  - ✅ 업비트(COIN)·환율(USD/KRW) 2개 외부 시세 클라이언트를 `infra/price/`에 신설(`UpbitPriceClient`/`ExchangeRateClient`). 공통 인터페이스로 억지로 묶지 않았다 — 응답 스키마·에러 케이스가 서로 달라 얕은 추상화가 되는 것을 피하기 위함. `domain/service/PriceService`가 `AssetType`으로 분기해 라우팅만 담당하고 도메인 값 객체 `Price`를 반환한다(웹 계층 비의존 — Task 023이 그대로 재사용할 수 있게). `GET /v1/assets/{id}/price` 신규 엔드포인트로 실제 호출까지 연결했고, 소유권 검증은 `SimulationService`와 동일하게 `PriceService`가 `AssetRepository`를 직접 조회해 자체 처리한다(404 `ASSET_NOT_FOUND` 통일). Circuit Breaker(Resilience4j `resilience4j-spring-boot4:2.4.0`)로 반복 실패 시 즉시 `ExternalPriceApiException`(503 `EXTERNAL_API_DOWN`)으로 전환, WireMock(`wiremock-standalone:3.13.2`)으로 정상·5xx·타임아웃·Circuit Breaker Open 4가지 시나리오를 전부 검증했다.
  - ✅ **STOCK(국내 주식) 시세는 이번 Task 범위에서 완전히 제외됐다** — **단, 아래 「[후속] STOCK 시세 연동 완료」 항목에서 번복됨.** 이 문단 시점에는 유효했던 결정이나, 같은 Task 안에서 사용자가 벤더를 확정하며 이어서 STOCK 클라이언트를 실제로 구현했다. 착수 전 계획은 업비트·KIS·환율 3개였으나, 착수 중 사용자가 KIS 오픈API 개인 발급 키의 약관("시세정보는 계좌 보유 개인이 본인 투자 목적에 한해 이용, 제3자 제공 불가")을 재확인해 다중 사용자 서비스에는 애초에 쓸 수 없었음을 발견했다. 대안 조사 결과 KRX Open API(상업적 이용 금지)·코스콤 오픈API(법인만 이용 가능, 개인사업자도 불가)·Twelve Data 무료 플랜(제3자 표시 금지, 유료도 표시 범위 불명확)이 전부 부적합했고, 공공데이터포털(금융위원회 증권상품시세정보, CC-Zero 라이선스, 전일 종가 기준)은 법적으로는 문제없으나 실시간이 아니라는 이유로 사용자가 보류를 결정했다. 유료 벤더(FnGuide·DeepSearch·알파스퀘어 등)는 실제 계약 조건 확인이 필요해 사용자가 직접 연락 중이며, 벤더가 정해지면 STOCK 클라이언트 구현을 후속 Task로 착수한다 — `PriceService`가 클라이언트별로 독립된 구조라 STOCK 클라이언트 추가 시 다른 코드는 건드릴 필요가 없다. STOCK·CASH(KRW) 자산에 시세를 요청하면 둘 다 `PriceUnavailableException`(400 `PRICE_NOT_APPLICABLE`, 서로 다른 메시지로 로그 구분 가능)으로 응답한다.
  - ✅ 업비트·환율 모두 이번 Task에서는 WireMock으로만 검증했고 실서버 연결은 후속 과제로 남겼다 — 둘 다 API 키가 필요 없어 기술적으로는 즉시 실연동이 가능하지만, KIS(실키 미발급)와 세 클라이언트를 동일한 방식으로 다루기 위해 일부러 함께 미뤘다.
  - ✅ Resilience4j `resilience4j-spring-boot4:2.4.0`의 Spring Boot 4.1 호환성을 착수 즉시 실측(`./gradlew dependencies`로 하위 모듈 트리 확인) — 구 PRD 시점엔 "미확정"이었던 리스크가 해소됐음을 확인했고, Retry 전용 축소안은 불필요했다. 다만 CB 파라미터 기본값(`minimumNumberOfCalls=100`)으로는 회로가 열리는 데 100번 호출이 필요해 테스트가 비현실적이었다 — `application.yml`에 `resilience4j.circuitbreaker.instances.{upbit,exchange-rate}`를 `slidingWindowSize=4`로 명시 설정해, 테스트를 가능하게 하는 동시에 실제 운영 튜닝까지 겸해 해소했다.
  - ✅ Fallback은 이 Task 시점엔 Redis 캐시가 아직 없어 "장애 시 직전 시세 재사용"이 아니라 "장애를 503 `EXTERNAL_API_DOWN`으로 명확히 응답"하는 수준으로 한정했다. **(현행화, Task 022에서 해소)** Redis 캐시·"Stale 시세"(`isStale`/206) 표시가 Task 022에서 실제로 구현됐다 — 캐시에 값이 없을 때만 이 문단의 503 경로가 남는다.
  - ✅ 환율 API(`fawazahmed0/exchange-api`)의 실제 엔드포인트를 착수 중 재확인해 계획 단계의 잘못된 가정(단일 통화쌍 응답 엔드포인트가 존재한다는 가정)을 정정했다 — 실제로는 `usd.json` 하나뿐이고 USD 기준 전체 환율표(수백 개 통화 키)를 반환하므로, `Map<String, BigDecimal>`로 받아 `krw` 키만 추출하도록 구현했다.
  - ✅ 구현 중 발견한 Spring Boot 4 함정 1건을 `CLAUDE.md` 「Spring Boot 4 특이사항」 표에 추가: `spring-boot-starter-web`만으로는 `RestClientAutoConfiguration`이 로드되지 않아 `RestClient.Builder` 빈이 없다는 `NoSuchBeanDefinitionException`이 남는다 — `spring-boot-restclient` 모듈 별도 필요.
  - ✅ 테스트 개수: 백엔드 124개(Task 019 기준) → **146개**(+22: `PriceTest` 3·`PricePropertiesTest` 2·`UpbitPriceClientTest` 3·`ExchangeRateClientTest` 3·`PriceServiceTest` 5·`AssetPriceIntegrationTest` 5·`PriceClientCircuitBreakerTest` 1). 매 서브태스크마다 `--rerun-tasks`로 캐시가 아닌 실제 재실행 결과를 직접 확인했고, 전체 스위트도 최종적으로 강제 재실행해 회귀 없음을 확인했다.
  - ✅ **[후속] STOCK(국내 주식) 시세 연동 완료** (2026-09-01, Task 021 완료 이후 사용자가 벤더를 직접 확정하며 이어서 진행). 위 「STOCK 완전 제외」 결정 이후, 사용자가 대안으로 KRX Open API를 재검토했으나 실측 결과 KIS와 동일한 구조의 제약이 있어 배제됨을 확인했다 — 실제 약관(openapi.krx.co.kr) 원문: 제6조 2항 "API 이용자는 API 서비스를 비상업적인 목적으로만 이용할 수 있으며, API 서비스를 이용한 결과에 대한 대가를 제3자에게 청구해서는 아니된다", 제11조 2항 "API 이용자는 한국거래소로부터 제공받은 정보를 제3자에게 제공할 수 없다". 최종적으로 **공공데이터포털의 "금융위원회_주식시세정보"(data.go.kr dataset 15094808)로 확정** — 이용허락범위 제한 없음(상업적 활용·재배포 가능)·무료·일 1회 갱신(EOD, 전일 종가 기준).
    - `infra/price/StockPriceClient` 신설 — 업비트/환율 클라이언트와 동일한 RestClient+Resilience4j Circuit Breaker+fallback 구조. `domain/service/PriceService`의 STOCK 분기를 `PriceUnavailableException` 스텁에서 실제 호출로 교체.
    - `Price.asOf`는 API 응답의 기준일자(`basDt`)를 `Asia/Seoul` 자정 `Instant`로 변환한 값을 쓰도록 설계했다 — EOD 데이터라 `Instant.now()`를 쓰면 실시간처럼 사용자를 오도하기 때문.
    - serviceKey는 이 프로젝트 최초로 비밀값이 필요한 외부 API 클라이언트라, `JwtProperties.secret`과 동일하게 `${ALLFOLIO_STOCK_SERVICE_KEY:}` 환경변수 위임 패턴을 적용했다(`.env` 로컬 사용 시 `.gitignore` 이미 포함, `set -a && source .env && set +a`로 셸에 로드 후 `./gradlew bootRun`).
    - 사용자가 실제 활용신청으로 서비스키를 발급받아 curl로 직접 재검증까지 완료(2026-09-01): (a) 응답의 `items.item`은 배열, (b) `basDt` 생략 시 최신 거래일 데이터가 반환됨(예: 호출일 9/1에 `basDt: "20260831"`), (c) `clpr` 등 숫자 필드가 JSON에서 따옴표 붙은 문자열로 옴(`"260000"`) — 활용가이드 문서에 없던 사실이나 이 프로젝트의 Jackson이 `BigDecimal` 필드로 문자열 토큰을 자동 변환해줘서 코드 수정은 불필요했음, (d) 인증 실패(잘못된 서비스키)는 정상 응답과 전혀 다른 `{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{...}}}` 스키마로 옴 — 기존 방어 로직(응답 필드 null 체크)이 코드 수정 없이 이 경우도 `ExternalPriceApiException`으로 정상 전환함을 실제 에러 응답을 재현한 테스트로 확인.
    - 이 API 전문 지식을 담은 `.claude/agents/stock-price-api.md` 신설(원본 `docs/오픈API 활용자가이드_금융위원회_주식시세정보.docx`의 전체 스펙을 옮겨 담음) — 위 (a)~(d) 실측 결과로 최신화됨.
    - `GET /v1/assets/{id}/price`에 STOCK 200 엔드투엔드 시나리오 추가(`AssetPriceIntegrationTest`), 기존 5개 시나리오는 그대로 유지.
    - 테스트 개수: 146개 → **151개**(+5: `StockPriceClientTest` 4·`AssetPriceIntegrationTest` +1). 전체 스위트 `--rerun-tasks` 강제 재실행으로 회귀 없음 확인.
  - ⚠️ 남은 갭:
    - 발급받은 실제 서비스키는 로컬 `.env`에만 있다 — 배포 환경(운영 서버)의 환경변수 반영은 별도 작업 필요
    - 공공데이터포털 개발계정 기준 일일 10,000건 호출 제한 — 운영 규모가 커지면 활용사례 등록으로 증설 신청 필요
    - `basDt` 생략 시 "최신 거래일" 반환은 1회 호출로만 확인됨 — 주말·공휴일이 여러 날 겹치는 경우 등 전체 케이스가 보장되는지는 추가 관찰 필요
    - 업비트·환율·STOCK 전부 WireMock 검증만 마쳤고 실서버 연결(WireMock→실제 API) 전환 시점은 미정
    - Resilience4j CB 파라미터(`failureRateThreshold` 등)는 실측 트래픽 없이 보수적 기본값으로 설정됨 — 운영 관측 후 조정 필요
    - `allfolio.price.fetch.duration` 메트릭에 대한 Prometheus 히스토그램 설정(`management.metrics.distribution.percentiles-histogram`)이 아직 없음(시뮬레이터의 `allfolio.simulation.duration`만 설정돼 있음) — 필요해지면 추가

- **Task 022: Redis 캐시 및 요청 Throttling** ✅ — 완료 (2026-09-02)
  - ✅ `infra/cache/PriceCacheStore`(Redis read-through 캐시)와 `infra/cache/PriceThrottle`(Lua 스크립트 기반 원자적 카운터)를 신설했다. `PriceService`가 둘을 오케스트레이션한다: 소유권 조회(캐시 키가 자산의 ticker/currency에 의존해 선행 필요 — 계획 단계의 "캐시 먼저" 가정을 실제 구현 시 정정) → 캐시 조회(fresh면 Throttle 없이 즉시 반환) → Throttle 확인 → 외부 클라이언트 호출 → 성공 시 캐시 저장/실패 시 stale 값 폴백, 폴백할 캐시조차 없으면 기존과 동일하게 503. 캐시 키는 사용자 정보 없는 시장 데이터 식별자(`price:{assetType}:{ticker}`, CASH는 `price:CASH:{currency}`)라 여러 사용자가 같은 종목을 조회해도 캐시를 공유한다.
  - ✅ 자산 유형별 freshTtl·stale 폴백 한계를 사용자 확정 정책대로 설정: COIN(업비트 실시간) 10초, STOCK·CASH-USD(공공데이터포털 EOD·환율, 일 단위 갱신) 12시간, staleCeiling(Redis 최종 만료) 24시간, 사용자당 Throttle 한도 초당 1건 — `allfolio.price-cache`/`allfolio.price-throttle`로 application.yml에 노출해 운영 중 조정 가능. **캐시 히트(외부 API 미호출)는 Throttle 소모 대상에서 제외** — Throttling의 목적(외부 API 남용 방지)과 정확히 일치시키기 위한 사용자 확정 정책.
  - ✅ ROADMAP이 계획 단계에서 언급한 Lettuce `INCREX` 지원 여부 실측 결과: Redis에는 INCR+EXPIRE를 원자적으로 묶은 그런 명령이 애초에 존재하지 않았다(redis.io 공식 rate-limiter 튜토리얼 등으로 확인) — Lua 스크립트로 두 명령을 하나의 원자적 실행 단위로 묶는 표준 방식으로 확정했다. count==1(윈도우의 첫 호출)일 때만 만료시간을 걸어 고정 윈도우로 동작시킨다. 초 단위 `EXPIRE`가 아닌 밀리초 단위 `PEXPIRE`를 쓰는데, window가 1초 미만이면 `EXPIRE`의 초 단위 인자가 0으로 잘려 키가 즉시 삭제되는 버그를 테스트로 실측했기 때문이다.
  - ✅ 구현 중 Spring Boot 4 함정 1건을 추가 발견해 `.claude/rules/spring-boot-4.md`에 기록: Spring Boot 4.1이 기본 JSON 라이브러리를 Jackson 3(`tools.jackson.*`)로 전환해 `com.fasterxml.jackson.databind.ObjectMapper`(Jackson 2) 빈이 더 이상 자동 등록되지 않는다 — Spring Data Redis의 `Jackson2JsonRedisSerializer`(deprecated)는 이 빈을 찾지 못해 `NoSuchBeanDefinitionException`이 남았고, Jackson 3 기반 `JacksonJsonRedisSerializer`로 교체해 해결했다.
  - ✅ Redis 8.8 Testcontainers 이미지 가용 여부를 실측 확인(`redis:8.8`/`redis:8.8-alpine` 모두 정상 pull·기동) — ROADMAP 「Phase 3 착수 전」 확인 항목이 해소됐다. `docker-compose.yml`의 주석 처리된 redis 블록도 해제했다.
  - ✅ `GET /v1/assets/{id}/price` 응답에 `isStale` 필드를 추가하고 stale 응답은 206 Partial Content로 구분했다(에러가 아닌 성공 응답이라 `{code,message,timestamp}` 포맷을 쓰지 않음 — ROADMAP의 'PRICE_STALE' 표현은 이 필드/상태코드 조합을 가리키는 것으로 해석). Throttle 한도 초과는 신규 `PriceRateLimitExceededException` → 429 `PRICE_RATE_LIMITED`로 매핑했다.
  - ✅ `AssetPriceIntegrationTest`에 캐시 히트(WireMock 호출 횟수로 외부 API 미호출 검증)·stale 폴백(206)·Throttle 초과(429) 3가지 엔드투엔드 시나리오를 추가했다. 검증 기준에 따라 캐시·Throttle 로직을 임시로 무력화해 이 3개 시나리오만 정확히 실패함을 확인(교차 검증)한 뒤 `git diff`로 흔적 없이 원복했음을 확인했다.
  - ✅ 구현 중 발견한 테스트 인프라 회귀와 해결: stale 시나리오를 빠르게 검증하려고 설정이 다른(짧은 freshTtl) 별도 Spring 컨텍스트를 만들었더니, 여러 컨텍스트가 공유 PostgreSQL Testcontainer의 동시 연결 수 제한을 넘겨 무관한 `UpbitPriceClientTest`가 `FATAL: sorry, too many clients already`로 깨지는 것을 실측했다 — 새 컨텍스트를 만들지 않고 기존 `AssetPriceIntegrationTest` 컨텍스트를 재사용(기본 freshTtl 10초를 실제로 대기)하는 방식으로 되돌려 해결했다.
  - ✅ 테스트 개수: 170개(신규 파일 `PriceCacheStoreTest` 3·`PriceThrottleTest` 2 + 기존 파일 확장 `PricePropertiesTest` +2·`PriceServiceTest` +3·`AssetPriceIntegrationTest` +3, 총 +13). 참고: Task 021 완료 기록의 151개는 이 Task 착수 시점 실측 총합(157개)과 차이가 있었다 — Task 021 STOCK 후속 작업 중 늘어난 일부 테스트(`PriceClientCircuitBreakerTest`·`ExchangeRateClientTest`·`PricePropertiesTest`)가 그 기록에 온전히 반영되지 않았던 것으로 보인다. 매 서브태스크마다 `--rerun-tasks`로 강제 재실행했고, 전체 스위트도 여러 차례 재실행해 회귀 없음을 확인했다.
  - ✅ `code-reviewer` 에이전트로 완료 후 독립 검증(2026-09-04) — Major 2건 발견 즉시 수정:
    - CASH(KRW)는 캐시에 절대 저장되지 않아 매번 캐시 미스가 나는데, Throttle 소모가 그보다 먼저 일어나 반복 요청 시 400 `PRICE_NOT_APPLICABLE` 대신 429 `PRICE_RATE_LIMITED`가 나가고, 사용자 단위 Throttle이라 다른 정상 자산 조회까지 막는 문제가 있었다 — `PriceService.getPrice()`에서 CASH(KRW) 판정을 캐시 조회·Throttle보다 앞으로 옮겨 해결
    - Redis 장애 시 `PriceCacheStore`/`PriceThrottle`의 예외가 그대로 전파돼 이 엔드포인트가 이 표(200/206/400/404/429/503)에 없는 500을 반환하던 문제 — 캐시 조회 실패는 캐시 미스로, 캐시 저장 실패는 무시, Throttle 확인 실패는 fail-open(허용)으로 처리하도록 `DataAccessException`을 잡아 방어
    - Minor 8건도 함께 반영: 테스트에 남아있던 "새 컨텍스트를 안 만든다"는 서술을 실제로 맞게 고침(`PriceThrottleTest`가 짧은 window를 얻으려 `@DynamicPropertySource`로 만들던 별도 컨텍스트를 제거하고 기본값 1초를 그대로 사용), `PriceCacheStoreTest`/`PriceServiceTest`의 BigDecimal 비교를 `.compareTo()` 기준으로 강화, `PriceThrottle`의 `RedisScript`를 `static final`로 캐싱, 완전히 중복이던 `CacheLookup`(항상 `!PricedQuote.stale()`과 동일한 `fresh` 필드만 얹고 있었음)을 제거하고 `PriceCacheStore.find()`가 `Optional<PricedQuote>`를 바로 반환하도록 단순화, `CLAUDE.md`/`PRD.md`의 "다음은 Task 022" 문구를 Task 023 기준으로 갱신
  - ⚠️ 남은 갭:
    - freshTtl·staleCeiling·Throttle 한도는 실측 트래픽 없이 합리적 추정치로 설정됨 — 운영 관측 후 조정 필요
    - 캐시 워밍업(콜드 스타트) 전략 없음 — 배포 직후에는 모든 요청이 캐시 미스로 시작
    - "설정이 다른 Spring 테스트 컨텍스트가 늘어날수록 공유 PostgreSQL 연결이 누적되는" 구조적 취약점 자체는 여전히 남아있다(`PriceThrottleTest`가 만들던 회피 가능한 컨텍스트 1개는 제거했지만, `AssetPriceIntegrationTest` 등 WireMock 포트가 서로 달라 불가피하게 갈라지는 컨텍스트들은 그대로다) — 테스트용 Hikari 풀 크기 축소 등 근본적인 인프라 개선은 별도 검토 필요
    - ✅ **(Task 023에서 해소)** 사용자당 초당 1건 Throttle 한도가 Task 023(포트폴리오 평가 시 보유 자산 수만큼 시세를 한 번에 조회)과 충돌할 가능성 — Throttle을 `GET /v1/assets/{id}/price` 단건 엔드포인트 전용 제약으로 범위를 좁히는 것으로 확정(사용자 확정 정책, Task 023 항목 참고)

- **Task 023: 포트폴리오 평가금액·비중·손익 (F005b)** ✅ — 완료 (2026-09-04)
  - ✅ Task 013이 계약에만 넣어두고 항상 `null`로 내보내던 5개 필드(`PortfolioItemResponse`의 `evaluationKrw`·`unrealizedPnl`·`weight`, `PortfolioResponse`의 `totalEvaluationKrw`·`totalUnrealizedPnl`)를 Task 021~022가 만든 시세 연동(`PriceService`)+Redis 캐시를 **그대로 재사용해** 실제 값으로 채웠다. 새 외부 클라이언트·새 캐시 계층을 만들지 않았다 — Task 021이 `PriceService`를 웹 계층 비의존으로 설계해둔 의도(도메인 값 객체 `Price`/`PricedQuote` 반환)가 여기서 회수됐다.
  - ✅ **Throttle 범위 재정의(사용자 확정 정책)**: `GET /v1/portfolio`에는 Task 022의 "사용자당 초당 1건" Throttle을 적용하지 않고, Throttle을 `GET /v1/assets/{id}/price` 단건 엔드포인트 **전용 제약**으로 범위를 좁혔다. 포트폴리오는 보유 자산 수만큼 시세를 한 번에 조회해야 하므로 기존 한도를 그대로 적용하면 자산이 2개 이상인 사용자는 사실상 매번 429를 겪는다(Task 022가 「남은 갭」으로 남긴 이월 항목의 해소). 구현은 `PriceService`에 신규 `quoteForPortfolio(Asset): Optional<PricedQuote>`를 추가하고, 기존 `getPrice(userId, assetId)`는 시그니처·동작 그대로 두는 방식이다 — 캐시조회~외부호출~stale 폴백~캐시저장 공통 경로만 `resolve(..., enforceThrottle)` private 헬퍼로 추출해 두 진입점이 공유한다(Throttle 소모 여부만 플래그로 갈린다).
  - ✅ **부분 실패 허용(사용자 확정 정책)**: 보유 자산 일부의 시세 조회가 실패해도(429/503/Circuit Breaker Open/파싱 실패 등) `GET /v1/portfolio`는 **항상 200**을 반환한다. `quoteForPortfolio()`는 어떤 예외든 `Optional.empty()`로 흡수하고(실패는 `log.warn`으로만 남김), 실패한 자산만 `evaluationKrw`/`unrealizedPnl`/`weight`가 `null`로 남는다. `totalEvaluationKrw`/`totalUnrealizedPnl`, 그리고 각 자산 `weight`의 분모는 **시세 조회에 성공한 자산들만으로** 계산한다 — 실패한 자산을 0원으로 취급하면 총액과 비중이 조용히 왜곡되기 때문. 성공한 자산이 하나도 없으면 두 합계는 `null`이다. `quoteForPortfolio()`는 CASH(KRW)도 외부 호출 없이 즉시 빈 값을 반환한다(시세 조회 대상이 아니라 `quantity` 자체가 평가금액).
  - ✅ **`TransactionTemplate` 채택(설계 결정)**: `PortfolioService.listPortfolio()`를 "DB 조회(트랜잭션 안) → 시세 조회(트랜잭션 밖에서 자산별 순회)" 2단계로 재구성했다. `PriceService`는 외부 API 호출 중 DB 커넥션이 점유되는 것을 막으려 의도적으로 트랜잭션을 두르지 않는 설계라, DB 조회 트랜잭션이 먼저 끝난 뒤에 호출해야 한다. 처음엔 DB 조회 부분만 private 메서드로 떼고 `@Transactional`을 붙였으나 **Spring AOP의 self-invocation 한계**(같은 빈 내부 호출은 프록시를 거치지 않아 트랜잭션이 예외 없이 조용히 무시된다)로 실제로는 동작하지 않았다 — 클래스 분리 대신 `TransactionTemplate`(read-only)으로 DB 조회 블록만 명시적으로 감싸는 방식으로 확정했다. 중간 상태는 private record `ItemDraft`로 옮겨 트랜잭션 밖에서 재사용한다.
  - ✅ KRW 환산 규칙: STOCK/COIN/CASH(KRW)는 시세가 이미 원화라 그대로 쓰고, **CASH(USD)만** 조회한 환율(환율 시세 자체가 "1달러 = x원" 형태의 원화 환산값)로 평가금액과 취득원가를 **함께** 환산한다 — `cost`는 USD 스케일로 저장돼 있어 평가금액만 환산하면 손익이 통화가 뒤섞인 값이 된다. 평가금액·손익은 KRW 정수 스케일(scale 0, HALF_UP), 비중은 `domain/PrecisionScale`에 신설한 `WEIGHT_SCALE = 2`(HALF_UP)로 계산한다. `weight`는 전체 합계가 확정된 뒤에야 계산 가능해 항목별 계산(`priceItem`)과 분리된 두 번째 패스(`withWeight`)로 둔다.
  - ✅ 통합 테스트에 WireMock 3개(업비트·공공데이터포털·환율)를 붙여 `PortfolioIntegrationTest`에 3개 시나리오를 추가했다: (a) 자산 유형이 섞인 포트폴리오의 평가금액·손익·비중·합계 정상 계산 (b) 자산 5개(Throttle 한도인 초당 1건을 크게 초과)를 한 번에 조회해도 **429 없이 전부 처리** (c) 자산 하나만 시세 실패 시 그 항목만 `null`이고 나머지 항목·합계·비중은 정상. `PortfolioServiceTest`(신규, 단위 3개)·`PriceServiceTest`(+3: CASH(KRW) 즉시 빈 값·외부 API 실패 시 예외 대신 빈 값·반복 호출해도 Throttle 미소모)까지 총 +9개.
  - ✅ 테스트 개수: 179개(신규 파일 `PortfolioServiceTest` 3 + 기존 파일 확장 `PriceServiceTest` +3·`PortfolioIntegrationTest` +3, 총 +9). 구현 중 실제 **테스트 격리 버그**를 발견해 함께 수정했다 — `PortfolioIntegrationTest`가 공유하는 단일 Spring 컨텍스트에서 Resilience4j CircuitBreaker(`upbit`/`exchange-rate`/`stock`)의 실패 카운트가 테스트 사이에 누적돼, 부분 실패 시나리오를 돌린 뒤 정상 시나리오가 Open 상태를 물려받아 깨졌다. `@BeforeEach`에서 WireMock `resetAll()`과 함께 세 CircuitBreaker를 `reset()`하도록 해 해결했다.
  - ✅ 프론트엔드는 실데이터로 검증만 하고 최소 조정에 그쳤다 — `PortfolioPage.tsx`/`AssetDetailPage.tsx`는 Task 018에서 이미 null/non-null 양쪽 렌더링이 구현돼 있어 새 로직이 필요 없었다. 실제 백엔드+브라우저로 확인한 결과 렌더링은 정상이었고, 대신 **실데이터와 모순되는 문구 4곳**을 고쳤다: 총액 옆 `시세 연동 전` 표식 → `시세 조회 실패`(서버가 성공 자산만으로 총액을 내므로 총액 `null`은 "연동 전"이 아니라 "전부 실패"를 뜻한다), 합계 카드의 "평가금액·평가손익은 시세를 연동하면 채워집니다"(값이 찍힌 화면에서 스스로를 부정) 삭제 후 **부분 실패 시에만** "시세를 불러오지 못한 자산 N건은 이 총액에서 빠졌습니다" 조건부 안내 추가, 상세 화면의 같은 문구를 "이 종목의 시세를 불러오지 못해…" 조건부 안내로 교체, 차트 자리 문구를 "가격 흐름 차트는 준비 중입니다"로(아직 없는 것은 시세 연동이 아니라 차트 자체 — Task 025 범위). 조회 실패 색은 `loss`(청, "하락")가 아닌 `alarm`을 쓴다 — 조회 실패가 가격 하락으로 읽히지 않도록. `docs/DESIGN.md`도 함께 갱신됐다.
  - ✅ `code-reviewer` 에이전트 독립 검증(2026-09-04) — Blocker 0건. Major 3건 발견, 전부 수정 완료:
    - **(수정 완료)** COIN 자산의 `unrealizedPnl`이 소수 8자리로 잘못 직렬화되는 스케일 버그. `evaluationKrw`(스케일 0)에서 COIN `cost`(스케일 8)를 빼면 `BigDecimal.subtract()`가 더 큰 스케일을 따라가 `"1000000"`이 아니라 `"1000000.00000000"`이 나갔다. `priceItem()`의 if/else 분기 이후 단일 지점에서 `unrealizedPnl`을 스케일 0(HALF_UP)으로 재정규화해 해결. 신규 단언(`isEqualByComparingTo` 대신 정확한 문자열 `isEqualTo`)이 스케일까지 잡는지 뮤테이션 테스트(정규화 코드 임시 제거 → 실패 확인 → 원복)로 실증
    - **(수정 완료)** Throttle을 뺀 뒤 대체 상한이 없어, 존재하지 않는 티커로 등록한 자산이 있으면 `GET /v1/portfolio` 반복 호출마다 외부 API가 상한 없이 불릴 수 있던 문제(`TickerNotFoundException`이 Circuit Breaker 실패 집계에서 제외돼 회로도 안 열림). `PriceCacheStore`에 짧은 TTL(30초) 부정 캐싱(`markFailed`/`hasRecentFailure`, `StringRedisTemplate` 기반)을 추가해 `quoteForPortfolio()` 전용으로 적용(단건 조회 API `getPrice()`는 기존 Throttle이 있어 대상 아님)
    - **(수정 완료)** STOCK/COIN을 KRW가 아닌 통화(예: USD)로 등록하면 `unrealizedPnl`이 KRW 평가금액에서 USD 원가를 빼는 통화 혼합 값이 되던 문제 — 시세는 항상 KRW인데(업비트 KRW 마켓·국내 종가) 원가는 자산 통화로 저장돼 있어서다. 실제 등록 화면이 STOCK도 KRW/USD 2지선다를 허용해 도달 가능한 케이스였다(`PortfolioIntegrationTest`의 기존 AAPL 테스트가 이미 이 조합을 검증 대상으로 삼고 있었음). `unrealizedPnl`은 자산 통화가 KRW일 때만 계산하고 그 외엔 `null`로 남기도록 수정 — `evaluationKrw`는 통화와 무관하게 유효한 값이라 그대로 계산되고 `weight`에도 정상 반영된다
    - Minor 8건은 이번엔 보류 — 환차손익 표시 불가(CASH-USD 구조상 항상 0), stale 시세 여부가 포트폴리오 응답에 미표시, 예외 흡수 범위(`RuntimeException` 광범위 catch), 프론트 신규 조건부 문구 2곳 테스트 부재, 문서 2곳(`frontend/src/api/types.ts`·`SimulateAvgPriceResponse` Javadoc) 잔여 구문 등 — 다음 착수 시 재검토
    - 재검증: 테스트 개수 179개 → **184개**(+5: `PriceServiceTest` +2·`PortfolioIntegrationTest` +1·`PriceCacheStoreTest` +2). `./gradlew test --rerun-tasks`(전체 스위트) 184개 전부 통과, `AssetPriceIntegrationTest`(단건 조회 API) 별도 재실행으로 부정 캐싱 추가가 기존 Throttle/CASH(KRW) 400 시나리오를 깨지 않았음을 확인
  - ⚠️ 남은 갭:
    - **시뮬레이터 `currentWeight`/`expectedWeight`는 이번 범위에서 제외**(사용자 확정, 후속 과제). 이 두 값을 채우려면 분모인 전체 포트폴리오 평가금액이 필요하고, 그러려면 `POST /v1/simulate/avg-price` 한 번에 보유 자산 수만큼 외부 시세를 조회해야 해 기존 "물타기 시뮬레이터 P99 ≤ 5ms(Holding 단건 DB 조회 + In-Memory 계산, 외부 API 없음)" 성능 목표(`domain/CLAUDE.md`·아래 「성능 KPI」)와 구조적으로 충돌한다. 착수 시 (a) KPI를 완화할지 (b) 비중만 별도 엔드포인트/캐시로 뺄지부터 정해야 한다
    - 자산이 많은 사용자는 시세를 **순차(순회) 조회**하므로 캐시가 비어 있는 콜드 스타트에서 응답이 느려질 수 있다 — 병렬화(Virtual Threads 기반 fan-out)는 이번 범위 밖으로 두었다. 캐시가 더워진 뒤에는 대부분 Redis 히트로 끝난다
    - 프론트 검증 중, 실 종목(예: `005380` 현대차)이 `ALLFOLIO_STOCK_SERVICE_KEY`를 정상 로드한 상태에서도 시세 조회에 실패하는 현상을 관측했다 — 부분 실패 정책 덕에 화면은 정상 동작(해당 항목만 `null`)했다. 원인은 STOCK 시세 클라이언트(Task 021, 공공데이터포털) 소관이라 이번엔 손대지 않았고 **후속 확인이 필요하다**(`stock-price-api` 에이전트 소관)
    - `quoteForPortfolio()`에는 `getPrice()`가 갖고 있는 계측(`allfolio.price.fetch.duration` Timer)을 붙이지 않았다 — 포트폴리오 경로의 시세 조회 지연은 현재 메트릭에 잡히지 않는다

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
  - 웹폰트(Instrument Sans · Gothic A1 · Reddit Mono) self-host 전환 검토 — `frontend/index.html`이 Google Fonts CDN에서 로드해, 오프라인/제한된 네트워크 환경에서 로드 실패 시 `--font-sans` 폴백으로 떨어질 수 있음

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
| `POST` | `/v1/auth/refresh` | 200 / 401 | Refresh Token 회전(rotation) — 기존 토큰 즉시 폐기 + 새 Access+Refresh 쌍 발급, 실패 시 `INVALID_REFRESH_TOKEN` (F010, Task 019) |
| `POST` | `/v1/auth/logout` | 204 | Refresh Token 폐기. 인증 불필요(Refresh Token 소유가 곧 권한 증명), idempotent(존재하지 않거나 이미 폐기된 토큰도 204) (F010, Task 019) |
| `POST` | `/v1/assets` | 201 | 자산+Holding+거래이력(BUY) 단일 트랜잭션 생성 |
| `GET` | `/v1/assets` | 200 | 커서 페이지네이션 (`limit` 기본 20/max 100, `cursor`), 최신 등록 순 |
| `GET` | `/v1/assets/{id}` | 200 / 404 | 타 유저 접근 시 404 (ID 유출 방지) |
| `PUT` | `/v1/assets/{id}/holdings` | 200 / 409 | 낙관적 잠금 `version` 필수, 충돌 시 `HOLDING_CONFLICT` |
| `DELETE` | `/v1/assets/{id}` | 204 / 404 | `ON DELETE CASCADE`로 holdings·transactions 함께 삭제 |
| `GET` | `/v1/portfolio` | 200 | 취득원가(Task 013, F005a) + `evaluationKrw`·`unrealizedPnl`·`weight`·합계 2개를 실제 시세로 계산(Task 023, F005b). 단건 조회용 Throttle 미적용, 일부 자산의 시세 조회가 실패해도 항상 200이고 그 항목의 3개 필드만 `null` |
| `POST` | `/v1/simulate/avg-price` | 200 | DB 저장 없음 |
| `GET` | `/v1/assets/{id}/price` | 200 / 206 / 400 / 404 / 429 / 503 | 외부 시세 단건 조회(STOCK/COIN/CASH-USD). 타 유저 접근 시 404, STOCK·CASH(KRW) 자산에 요청 시 400 `PRICE_NOT_APPLICABLE`, 외부 API 장애 시 503 `EXTERNAL_API_DOWN`(Task 021). Redis 캐시 stale 폴백 시 206 + 응답 본문 `isStale:true`, 사용자당 초당 1건 Throttle 초과 시 429 `PRICE_RATE_LIMITED`(Task 022) |

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

### `GET /v1/assets/{id}/price` 응답 예시

```json
{ "amount": "71000", "currency": "KRW", "asOf": "2026-08-31T15:00:00Z" }
```

STOCK/COIN은 자산 통화 기준 스케일, CASH(USD)는 응답 통화(항상 KRW로 환산된 환율값) 기준 스케일이 적용된다 — `Price.amount`가 이미 라운딩된 값이므로 클라이언트가 재반올림할 필요는 없다. STOCK은 EOD(전일 종가) 데이터라 `asOf`가 조회 시각이 아닌 기준일자 자정(Asia/Seoul)이다.

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
      "quantity": "10.00000000", "avgPrice": "60000", "cost": "600000",
      "evaluationKrw": "700000", "unrealizedPnl": "100000", "weight": "100.00" }
  ],
  "totalCostByCurrency": { "KRW": "600000" },
  "totalEvaluationKrw": "700000",
  "totalUnrealizedPnl": "100000"
}
```

위 예시는 시세가 70,000원일 때다(10주 × 70,000 = 700,000, 손익 = 700,000 − 600,000 = 100,000). 보유 자산이 이 하나뿐이라 `weight`는 `"100.00"`이다 — 비중은 scale 2·HALF_UP이고(`PrecisionScale.WEIGHT_SCALE`), 분모는 **시세 조회에 성공한 자산들의 평가금액 합계**다. `evaluationKrw`·`unrealizedPnl`·합계 2개는 KRW 정수 스케일(scale 0, HALF_UP)이다.

`evaluationKrw`/`unrealizedPnl`/`weight`가 `null`로 내려오는 경우는 **그 자산의 시세 조회가 실패했을 때뿐이다**(Task 023의 부분 실패 허용 정책 — 응답 자체는 항상 200). 성공한 자산이 하나도 없으면 `totalEvaluationKrw`/`totalUnrealizedPnl`도 `null`이 된다. `totalCostByCurrency`는 시세와 무관하게 언제나 채워진다.

`quantity`는 자산유형·통화와 무관하게 항상 8자리 scale로 내려온다(시뮬레이터 `expectedQuantity`와 동일 규칙). `avgPrice`·`cost`는 자산유형/통화별 「금융 정밀도 규칙」(COIN 8자리, 그 외 통화 기준)을 따른다 — 이 스케일 결정은 `GET /v1/portfolio`에만 적용되며, `GET /v1/assets`(Task 012)는 여전히 DB `NUMERIC(28,8)` 왕복 스케일을 그대로 노출한다(위 Task 013 항목의 「남은 갭」 참고).

**주의**: `cost`는 반올림 전 원본 정밀도(`quantity × avgPrice` 원본값)로 계산한 뒤 스케일을 적용하고, `avgPrice`는 응답에 나가기 직전 별도로 반올림된다 — 즉 `cost`와 `quantity × 응답의 avgPrice`를 클라이언트가 직접 재계산하면 반올림 오차만큼(보통 통화 최소단위 이하) 다를 수 있다. 예: KRW 자산 평단가 원본이 `60000.75`면 응답은 `avgPrice: "60001"`(반올림)이지만 `cost`는 원본 `60000.75`로 계산해 반올림한 값이라 `quantity × 60001`과 정확히 일치하지 않는다. 화면 표시값(프론트가 별도로 반올림)에는 영향 없다.

`totalCostByCurrency`는 `items`에 담긴 모든 자산의 취득원가를 통화별로 합산한 값이다(위 예시는 자산 1건뿐이라 KRW 값이 그 자산의 `cost`와 같다). 자산이 둘 이상이거나 USD 자산이 섞이면 통화별 키가 함께 늘어난다 — 다중 자산·다중 통화 예시는 `frontend/src/pages/PortfolioPage.test.tsx`의 `portfolioResponseFixture` 참고.

`totalCostByCurrency`가 통화별 Map인 이유: 취득원가 합계를 단일 `totalCostKrw`로 두면 USD 자산이 섞였을 때 환율 없이는 정확한 값을 낼 수 없다. Task 023에서 환율이 연동된 뒤에도 **취득원가는 KRW로 환산하지 않고** 통화별로 나눠 담는다 — 과거 매수 시점의 원가를 오늘 환율로 환산하면 환차손익이 취득원가에 섞여 들어가기 때문이다(환율 환산은 평가금액·손익에만 적용된다).

`evaluationKrw`뿐 아니라 `unrealizedPnl`(`PortfolioItem`·`totalUnrealizedPnl` 둘 다)도 **항상 KRW 환산액**이다 — 원자산 통화(`currency`)가 아니다. PRD F005가 "전체 자산을 KRW 기준으로 환산해 평가금액·비중(%)·미실현 손익 표시"라고 명시하므로, 세 값(평가금액·비중·손익) 모두 같은 환산 기준을 따른다. `evaluationKrw`만 필드명에 `Krw`가 붙어 있어 헷갈리기 쉬운 지점이다. CASH(USD) 자산은 손익을 낼 때 취득원가도 같은 환율로 환산한 뒤 빼야 원화 기준 손익이 나온다(Task 023 구현).

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

`currentWeight`/`expectedWeight`는 포트폴리오 내 비중(%)이다. 분모가 전체 포트폴리오 평가금액이라 외부 시세가 있어야 계산되는데(F005b), **Task 023에서도 채우지 않고 `null`로 남겼다**(사용자 확정) — 이 한 번의 호출에 보유 자산 수만큼 외부 시세 조회가 딸려 오면 아래 「성능 KPI」의 "시뮬레이터 P99 ≤ 5ms(DB 단건 조회 + In-Memory 계산)"와 정면으로 충돌하기 때문이다. 후속 과제(Task 023 「남은 갭」 참고).

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

**구현된 코드 (Task 019)**: `INVALID_REFRESH_TOKEN`(401, Refresh Token이 존재하지 않거나·이미 폐기됐거나·만료됨 — 세 상황을 구분하지 않는 단일 코드로 응답해 토큰 존재 여부가 새지 않게 함)

**구현된 코드 (Task 021)**: `PRICE_NOT_APPLICABLE`(400, STOCK·CASH(KRW) 자산에 시세를 요청 — 서로 다른 메시지로 로그 구분 가능하나 코드는 동일), `EXTERNAL_API_DOWN`(503, 외부 시세 API 장애 또는 Circuit Breaker Open. 응답 파싱/매칭 실패(예: 존재하지 않는 티커)는 CB 실패 집계에서 제외되지만 클라이언트 응답 코드는 동일하게 503)

**구현된 코드 (Task 022)**: `PRICE_RATE_LIMITED`(429, 캐시 미스/스테일 상태에서 사용자당 초당 1건 Throttle 한도 초과 — 캐시 히트는 이 한도를 소모하지 않음). 캐시 stale 폴백은 에러가 아닌 성공 응답이라 이 표의 `{code,message,timestamp}` 포맷 대신 206 + 응답 본문 `isStale:true`로 표현한다

---

## 금융 정밀도 규칙

| 자산 유형 | 스케일 | RoundingMode |
|---|---|---|
| KRW 주식 | 0 | HALF_UP |
| USD 자산 | 4 | HALF_UP |
| 코인 | 8 | HALF_UP |
| 기타 통화(폴백, 예: JPY) | 2 | HALF_UP |
| 비중(%) | 2 | HALF_UP |

코인은 통화와 무관하게 항상 스케일 8이 우선 적용된다(위 표의 "코인" 행이 통화별 행보다 우선). KRW/USD 외 통화는 스케일 2로 폴백한다 — `domain/PrecisionScale.scaleFor()`/`scaleForCurrency()`(Task 016에서 `PortfolioService`/`SimulationService` 중복 로직을 통합한 공유 유틸)의 실제 코드 동작이며, `BigDecimalPrecisionTest`가 이 표 전체 조합을 고정한다.

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
| BigDecimal 스케일 규칙 누락 | ✅ (Task 016 해소) 위 「금융 정밀도 규칙」 표를 `domain/PrecisionScale` 유틸로 코드화, `BigDecimalPrecisionTest`로 고정, `PortfolioService`/`SimulationService`가 공유 |
| Optimistic Lock 고빈도 충돌 | 유저 수 적은 초기 단계이므로 단순 `@Version`으로 충분. 충돌 시 `HOLDING_CONFLICT` 409 응답으로 클라이언트가 재조회 후 재시도 |
| 프론트-백엔드 API 계약 어긋남 | Task 006에서 TypeScript 타입(`frontend/src/api/types.ts`)을 계약의 단일 출처로 삼아 프론트가 참조(Task 006~017 더미 데이터 단계에서는 `frontend/src/api/fixtures.ts` 더미 픽스처도 이 타입을 따르도록 병행 유지했으나, Task 018에서 실 API 연동과 함께 제거됨 — 이제는 `frontend/src/api/assetApi.ts`가 실제 백엔드 호출로 계약 일치 여부를 상시 검증). 백엔드는 `ApiContractSerializationTest`로 직렬화 형태를 고정 |
| 종목 중복 등록 허용에 따른 포트폴리오 집계 복잡도 | 「확정된 설계 결정」 #3 참조 — 티커별 합산이 아닌 자산(행) 단위 집계로 설계해 복잡도를 피한다 |

**해소된 리스크 (Task 002에서 실측 확인 완료)**
- Spring Boot 4.1.0 + Java 25 호환성 — 정상 동작 확인
- `uuidv7()` PG18 함수 지원 — Testcontainers PG18 이미지에서 확인됨
- Flyway 오토컨피규레이션 누락, Testcontainers 버전 협상 실패 — `.claude/rules/spring-boot-4.md` 참조

---

## Phase 전환 시 선행 확인 사항

**Phase 3 착수 전 (Task 021~022 관련)**
- ✅ **(Task 022에서 해소)** Lettuce의 "`INCREX`" 지원 여부 확인 — 실측 결과 Redis에는 INCR+EXPIRE를 원자적으로 묶은 그런 명령 자체가 존재하지 않았다. Lua Script로 두 명령을 원자적으로 묶는 표준 방식으로 확정
- ✅ **(Task 022에서 해소)** Redis 8.8 Testcontainers 이미지 가용 여부 확인 — `redis:8.8`/`redis:8.8-alpine` 모두 정상 pull·기동 확인
- F005b·F007(실시간 차트)·외부 API 연동의 상세 기술 명세는 본 저장소에 문서로 남아있지 않다. 필요 시 `git show cf24471:docs/PRD.md`로 구 PRD v1.2.0(업비트/KIS WebSocket 연동, SSE 이벤트 스키마, 환율 API, `price_snapshots` 파티셔닝 포함)을 열람해 참고할 것

**Task 004 착수 시**
- 프론트엔드는 `senior-frontend` 에이전트가 담당한다(컴포넌트·라우팅·상태·API 클라이언트·컴포넌트 테스트). Task 020의 Playwright MCP E2E는 착수 시점에 별도 QA 에이전트를 신설한다.
