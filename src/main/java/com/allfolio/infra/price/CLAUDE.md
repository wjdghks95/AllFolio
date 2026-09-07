# 외부 시세 클라이언트 규칙

이 API 자체의 상세 요청/응답 스키마는 `.claude/agents/stock-price-api.md`(공공데이터포털 주식시세 API 전문 문서)에 있다. 여기는 `infra/price/` 폴더 전체에 적용되는 설계 결정만 담는다.

## 클라이언트별로 독립 구현한다

`UpbitPriceClient`/`ExchangeRateClient`/`StockPriceClient`를 공통 인터페이스로 묶지 않았다. 응답 스키마·에러 케이스가 서로 달라 얕은 추상화가 되는 것을 피하기 위함이다. `domain/service/PriceService`가 `AssetType`으로 분기해 라우팅만 담당하고, 도메인 값 객체 `Price`를 반환한다(웹 계층 비의존). 새 시세 클라이언트를 추가할 때도 이 패턴을 그대로 따를 것 — 억지로 공통 인터페이스를 만들지 않는다.

## STOCK 시세는 EOD(전일 종가) 데이터다

`Price.asOf`는 API 응답의 기준일자(`basDt`)를 `Asia/Seoul` 자정 `Instant`로 변환한 값을 쓴다. `Instant.now()`를 쓰면 실시간 시세처럼 사용자를 오도하기 때문이다.

## 코인(COIN) 티커·마켓 코드·통화 규칙

사용자는 자산 등록 화면에서 코인 심볼만 입력한다("BTC"). `UpbitPriceClient.getPrice(ticker, currency)`가
자산의 `currency`로 업비트 마켓 코드를 조립한다 — KRW는 `KRW-BTC`, USD는 `USDT-BTC`로 매핑한다.
**업비트는 `USD-BTC` 같은 달러 마켓 자체를 지원하지 않는다**(원화 마켓과 스테이블코인 마켓만 존재) — USDT는
사실상 1달러에 고정된 스테이블코인이라는 전제로 USD의 대체 마켓으로 근사했다(2026-09-07 사용자 확정).

USDT 마켓에서 받은 값은 달러 표시 시세이므로, `PriceService.coinPrice()`가 CASH(USD)와 동일한
`ExchangeRateClient`로 원화 환산까지 마친 뒤에야 `Price`를 반환한다 — 환산을 빼먹으면 `evaluationKrw`에
달러 숫자가 원화인 것처럼 그대로 노출된다(실측 회귀 사례: `PriceServiceTest.coinUsdAssetConvertsUsdtQuoteToKrwUsingExchangeRate`).
이 환율 조회는 CASH(USD)가 쓰는 `price:CASH:USD` 캐시(freshTtl 12시간)를 그대로 재사용한다 — 그렇지
않으면 COIN의 짧은 freshTtl(10초) 주기로 환율 API가 불필요하게 반복 호출된다.

`Price.currency()`는 `getPrice()`에 넘긴 `currency` 인자가 아니라 **실제로 조회한 마켓 접두어**에서
도출한다. 이미 마켓 접두어가 포함된 티커(과거 데이터·직접 입력, 예: `"KRW-BTC"`)는 자산의 `currency`
필드와 어긋날 수 있는데, 인자를 그대로 믿으면 원화 시세에 환율을 또 곱하거나 달러 시세를 원화로 잘못
표기하게 된다(회귀 테스트: `UpbitPriceClientTest.getPriceDerivesCurrencyFromActualMarketNotFromMismatchedCurrencyArgument`).

같은 티커라도 통화별로 실제 조회 마켓과 환산 결과가 다르므로, 캐시 키(`price:COIN:{ticker}`)에도
KRW가 아닌 통화는 접미사로 반영한다(`price:COIN:{ticker}:{currency}`) — 그렇지 않으면 "BTC"를
KRW로 등록한 자산과 USD로 등록한 자산이 캐시를 공유해 서로 다른 가격을 덮어쓴다.

## 에러 매핑

- 외부 API 장애(반복 실패 → Circuit Breaker Open 포함): 503 `EXTERNAL_API_DOWN`
- 시세 개념이 없는 자산(CASH, 또는 벤더 미정 자산): 400 `PRICE_NOT_APPLICABLE`

## Resilience4j Circuit Breaker 파라미터

기본값 `minimumNumberOfCalls=100`으로는 회로가 열리는 데 100번 호출이 필요해 테스트가 비현실적이다. `application.yml`의 `resilience4j.circuitbreaker.instances.{upbit,exchange-rate,stock}`에 `slidingWindowSize=4` 등을 명시 설정해, 테스트를 가능하게 하는 동시에 실제 운영 튜닝까지 겸한다.

## 시크릿

`serviceKey`처럼 비밀값이 필요한 클라이언트는 `${ALLFOLIO_STOCK_SERVICE_KEY:}` 형태로 환경변수에 위임한다(`JwtProperties`와 동일 패턴). 코드·설정 파일에 하드코딩 금지.
