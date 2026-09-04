# 프론트엔드 폴더 규칙

Vite 개발 서버는 `/v1/*` 요청을 `localhost:8080`(Spring Boot)으로 프록시한다. 브라우저에서 같은 출처로 보이므로 개발 중에는 CORS 설정이 필요 없다. 백엔드(`./gradlew bootRun`)와 프론트(`npm run dev`)를 동시에 띄운 상태에서 `http://localhost:5173`으로 접속해 개발한다.

## 명령어

```bash
npm run test        # vitest run (1회 실행)
npm run test:watch  # vitest watch 모드
npm run typecheck   # tsc -b --noEmit
npm run lint        # oxlint
```

## 금액 계산은 `Dec`(big.js)로만 한다

`src/lib/big.ts`가 내보내는 `Dec`는 전역 설정을 건드리지 않는 독립 `Big()` 인스턴스이고 `strict = true`로 만들어져 있다 — 금액 계산에 원시 `number`를 넣으면(예: `Dec(0.1)`) **런타임에 즉시 throw**한다. 백엔드가 `double`/`float`을 금지하고 `BigDecimal`만 쓰는 것과 대칭되는 규칙([`.claude/rules/financial-precision.md`](../.claude/rules/financial-precision.md) 참고)이다. 금액을 다루는 코드에서 `new Big(...)`을 직접 만들지 말고 항상 `Dec` 인스턴스를 통해 계산할 것 — 화면에 표시할 땐 `toScaledString(value, scale)`로 반올림·자릿수를 맞춘다.
