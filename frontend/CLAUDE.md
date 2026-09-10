# 프론트엔드 폴더 규칙

Vite 개발 서버는 `/v1/*` 요청을 `localhost:8080`(Spring Boot)으로 프록시한다. 브라우저에서 같은 출처로 보이므로 개발 중에는 CORS 설정이 필요 없다. 백엔드(`./gradlew bootRun`)와 프론트(`npm run dev`)를 동시에 띄운 상태에서 `http://localhost:5173`으로 접속해 개발한다.

## 명령어

```bash
npm run dev         # Vite 개발 서버 (포트 5173)
npm run build       # 프로덕션 빌드 → dist/
npm run test        # vitest run (1회 실행)
npm run test:watch  # vitest watch 모드
npm run typecheck   # tsc -b --noEmit
npm run lint        # oxlint
```

## 디렉터리 구조

```
src/
  api/       백엔드 fetch 래퍼 (authApi.ts, assetApi.ts, types.ts, fixtures.ts)
  components/ 공통 UI 컴포넌트 (Button, Field, TextField, Alert, Card, ConfirmDialog, SegmentToggle)
  pages/     라우트별 페이지 (LoginPage, SignupPage, PortfolioPage, AssetNewPage, AssetDetailPage)
  lib/       순수 유틸 (big.ts, money.ts, validation.ts, simulate.ts, messages.ts)
  hooks/     커스텀 훅
```

## 화면 전환 후 알림 — Flash 메시지 패턴

Toast 대신 **라우터 state로 결과 전달** 후 이동 대상 페이지의 `Alert`로 렌더링한다:

```ts
// 송신 측
navigate('/portfolio', { state: { flash: { type: 'success', message: '...' } } });

// 수신 측 (PortfolioPage)
const [flash] = useState(() => location.state?.flash ?? null);  // 마운트 시 1회 캡처
```

`useState` 지연 초기화로 마운트 시점에 캡처해야 한다 — `location.state`를 매 렌더에서 읽으면 한 프레임 만에 사라진다(실측 버그).

## 금액 계산은 `Dec`(big.js)로만 한다

`src/lib/big.ts`가 내보내는 `Dec`는 전역 설정을 건드리지 않는 독립 `Big()` 인스턴스이고 `strict = true`로 만들어져 있다 — 금액 계산에 원시 `number`를 넣으면(예: `Dec(0.1)`) **런타임에 즉시 throw**한다. 백엔드가 `double`/`float`을 금지하고 `BigDecimal`만 쓰는 것과 대칭되는 규칙([`.claude/rules/financial-precision.md`](../.claude/rules/financial-precision.md) 참고)이다. 금액을 다루는 코드에서 `new Big(...)`을 직접 만들지 말고 항상 `Dec` 인스턴스를 통해 계산할 것 — 화면에 표시할 땐 `toScaledString(value, scale)`로 반올림·자릿수를 맞춘다.
