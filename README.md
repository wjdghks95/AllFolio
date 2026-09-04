# AllFolio

주식·코인·현금으로 흩어진 자산을 KRW 기준 한 화면에서 통합 조회하고, 추가 매수(물타기) 시 예상 평단가를 즉시 계산해주는 개인 투자자용 자산 관리 서비스입니다.

이 문서는 저장소를 처음 받았을 때 백엔드·프론트를 순서대로 띄우고, curl로 API가 실제로 동작하는지 눈으로 확인하는 절차를 안내합니다. 빌드·테스트 세부 명령어와 아키텍처 규칙은 [`CLAUDE.md`](CLAUDE.md)를 참고하세요.

## 사전 요구사항

- Java 25
- Node.js (프론트 `frontend/package.json` 기준)
- Docker / Docker Compose

## 1. 백엔드 기동

PostgreSQL을 먼저 띄웁니다.

```bash
docker compose up -d
```

앱은 `ALLFOLIO_JWT_SECRET` 환경변수가 없으면 **부팅 시 즉시 실패**합니다. 기본값이 빈 문자열로 되어 있는데, 이는 약한 키(빈 값)가 조용히 쓰이는 걸 막기 위한 의도된 동작입니다. 매번 새 키를 생성해서 실행하세요.

```bash
ALLFOLIO_JWT_SECRET=$(openssl rand -base64 32) ./gradlew bootRun
```

정상 기동됐는지 확인합니다.

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

## 2. 프론트 기동

```bash
cd frontend
npm install
npm run dev
```

브라우저에서 http://localhost:5173 으로 접속합니다. Vite 개발 서버가 `/v1/*` 요청을 `localhost:8080`(백엔드)으로 프록시(대신 전달)하므로, 브라우저 입장에서는 같은 출처로 보여 별도 CORS 설정 없이 개발할 수 있습니다.

> 현재 단계에서는 화면이 아직 더미 데이터로 채워져 있습니다. 프론트가 실제 API를 호출하도록 연동하는 작업은 `docs/ROADMAP.md`의 Task 018에서 진행됩니다.

## 3. curl로 API 흐름 확인하기

백엔드가 켜진 상태에서 아래를 순서대로 실행하면 회원가입부터 포트폴리오 조회까지 전체 흐름을 확인할 수 있습니다. (`jq`가 설치되어 있어야 합니다: `brew install jq`)

### 3-1. 회원가입 → 토큰 발급

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/v1/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.com","password":"password123"}' \
  | jq -r .accessToken)

echo $TOKEN
```

응답 예시(`TokenResponse`):

```json
{
  "accessToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### 3-2. 자산 등록

주식 1건을 등록합니다.

```bash
ASSET_ID=$(curl -s -X POST http://localhost:8080/v1/assets \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"ticker":"005930","name":"삼성전자","assetType":"STOCK","currency":"KRW","quantity":10,"avgPrice":70000}' \
  | jq -r .id)

echo $ASSET_ID
```

응답 예시(`AssetResponse`):

```json
{
  "id": "0191a2b3-...",
  "ticker": "005930",
  "name": "삼성전자",
  "assetType": "STOCK",
  "currency": "KRW",
  "quantity": "10",
  "avgPrice": "70000",
  "version": 0,
  "updatedAt": "2026-08-27T12:00:00Z"
}
```

현금 1건도 등록해봅니다. `avgPrice`는 생략할 수 있습니다(CASH는 평단가 개념이 없어 서버가 내부적으로 1을 넣습니다).

```bash
curl -s -X POST http://localhost:8080/v1/assets \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"ticker":"KRW-CASH","name":"원화 현금","assetType":"CASH","currency":"KRW","quantity":1000000}'
```

### 3-3. 자산 목록 조회

```bash
curl -s http://localhost:8080/v1/assets \
  -H "Authorization: Bearer $TOKEN" | jq
```

응답 예시(`AssetListResponse`) — 방금 등록한 자산들이 `items`에 보입니다. 등록 직후 응답과 달리 목록 조회에서는 `quantity`/`avgPrice`가 소수점 8자리로 채워져 내려옵니다(둘 다 같은 값을 가리키며, DB 왕복 여부에 따른 표현 차이일 뿐입니다).

```json
{
  "items": [
    { "id": "0191a2b4-...", "ticker": "KRW-CASH", "name": "원화 현금", "assetType": "CASH", "currency": "KRW", "quantity": "1000000.00000000", "avgPrice": "1.00000000", "version": 0, "updatedAt": "..." },
    { "id": "0191a2b3-...", "ticker": "005930", "name": "삼성전자", "assetType": "STOCK", "currency": "KRW", "quantity": "10.00000000", "avgPrice": "70000.00000000", "version": 0, "updatedAt": "..." }
  ],
  "nextCursor": null
}
```

### 3-4. 물타기 시뮬레이션

70,000원에 10주를 가진 종목을 65,000원에 5주 추가 매수하면 평단가가 어떻게 되는지 계산합니다.

```bash
curl -s -X POST http://localhost:8080/v1/simulate/avg-price \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"assetId\":\"$ASSET_ID\",\"additionalPrice\":65000,\"additionalQuantity\":5}" | jq
```

응답 예시(`SimulateAvgPriceResponse`):

```json
{
  "currentAvgPrice": "70000",
  "expectedAvgPrice": "68333",
  "expectedQuantity": "15.00000000",
  "currentWeight": null,
  "expectedWeight": null,
  "calculatedAt": "2026-08-27T12:00:01Z"
}
```

DB에는 아무것도 저장되지 않습니다 — 순수 계산 결과만 돌려줍니다. `expectedAvgPrice`에 소수점이 없는 건 반올림 오류가 아니라 통화별 소수 자리수 규칙 때문입니다(KRW는 0자리, USD는 4자리, 그 외 2자리, 코인은 통화 무관 8자리 — `PrecisionScale`). `expectedQuantity`만 자산 유형·통화와 무관하게 항상 8자리로 고정됩니다.

### 3-5. 포트폴리오 조회

```bash
curl -s http://localhost:8080/v1/portfolio \
  -H "Authorization: Bearer $TOKEN" | jq
```

응답 예시(`PortfolioResponse`) — `quantity`는 항상 8자리, `avgPrice`/`cost`/`totalCostByCurrency`는 시뮬레이션 응답과 같은 통화별 소수 자리수 규칙(KRW는 0자리)을 따릅니다. `evaluationKrw`/`unrealizedPnl`/`weight`는 외부 시세로 계산한 값이라 아래 숫자는 예시일 뿐, 실제로 curl을 실행하는 시점의 실시간 시세(이 예시는 삼성전자 75,000원 가정)에 따라 달라집니다.

```json
{
  "items": [
    {
      "assetId": "0191a2b3-...",
      "ticker": "005930",
      "name": "삼성전자",
      "assetType": "STOCK",
      "currency": "KRW",
      "quantity": "10.00000000",
      "avgPrice": "70000",
      "cost": "700000",
      "evaluationKrw": "750000",
      "unrealizedPnl": "50000",
      "weight": "42.86"
    }
  ],
  "totalCostByCurrency": { "KRW": "1700000" },
  "totalEvaluationKrw": "1750000",
  "totalUnrealizedPnl": "50000"
}
```

`ALLFOLIO_STOCK_SERVICE_KEY`(공공데이터포털 주식시세 API 키)를 설정하지 않았다면 STOCK 시세 조회만 실패합니다 — 이 경우 위 응답에서 STOCK 항목의 `evaluationKrw`/`unrealizedPnl`/`weight`만 `null`로 남고 나머지 필드·응답 자체(200)는 그대로 정상입니다(자산 하나의 시세 실패가 전체 조회를 막지 않는 부분 실패 허용 정책).

## 알아두면 좋은 점

- **`null`로 나오는 필드는 버그가 아닙니다.** `GET /v1/portfolio`의 `evaluationKrw`/`unrealizedPnl`/`weight`(자산별)·`totalEvaluationKrw`/`totalUnrealizedPnl`(합계)은 이제 실제 시세로 채워지지만, **그 자산의 시세 조회가 실패하면 그 자산만** 세 필드가 `null`로 남습니다(부분 실패 허용 — 나머지 자산과 응답 자체는 정상). `POST /v1/simulate/avg-price`의 `currentWeight`/`expectedWeight`는 이 값을 계산하려면 보유 자산 수만큼 시세를 추가로 조회해야 해 시뮬레이터의 성능 목표(P99 ≤ 5ms)와 충돌하므로 의도적으로 항상 `null`입니다(`docs/ROADMAP.md` Task 023 「남은 갭」 참고). 시세 없이 거짓 숫자를 내보내지 않기 위한 설계입니다.
- **에러 응답은 항상 3개 필드 고정**입니다: `{code, message, timestamp}`. 자주 보게 될 코드는 다음과 같습니다.

  | 코드 | 상태 | 상황 |
  |---|---|---|
  | `VALIDATION_ERROR` | 400 | 요청 필드 검증 실패 |
  | `UNAUTHORIZED` | 401 | 토큰 없음/무효 |
  | `INVALID_CREDENTIALS` | 401 | 로그인 이메일/비밀번호 불일치 |
  | `EMAIL_ALREADY_EXISTS` | 409 | 이미 가입된 이메일로 회원가입 시도 |
  | `ASSET_NOT_FOUND` | 404 | 존재하지 않거나 남의 자산 조회/수정/삭제 시도 |
  | `HOLDING_CONFLICT` | 409 | 낙관적 잠금 충돌(다른 요청이 먼저 수정) |

- **금액·수량·비중 값은 모두 JSON 문자열**로 내려옵니다(`BigDecimal`의 정밀도를 JSON 숫자 타입으로 옮기면 깨질 수 있어, 문자열로 고정한 계약). 소수 자리수는 값마다 다를 수 있습니다 — `quantity`는 항상 8자리, 금액류(`avgPrice`/`cost`/`expectedAvgPrice` 등)는 통화별 규칙(KRW 0자리, USD 4자리, 그 외 2자리, 코인은 8자리)을 따릅니다.
- 인증은 쿠키가 아니라 `Authorization: Bearer <accessToken>` 헤더 방식입니다.

## 더 알아보기

- [`CLAUDE.md`](CLAUDE.md) — 빌드/테스트 상세 명령어, 아키텍처, Spring Boot 4 특이사항
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — Phase/Task별 진행 상황, API 규격의 single source of truth
- [`docs/PRD.md`](docs/PRD.md) — 화면·기능 명세
