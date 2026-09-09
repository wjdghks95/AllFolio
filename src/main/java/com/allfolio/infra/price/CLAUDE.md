# 외부 시세 클라이언트 규칙

이 API 자체의 상세 요청/응답 스키마는 `.claude/agents/stock-price-api.md`(공공데이터포털 주식시세 API 전문 문서)·`.claude/agents/twelvedata-api.md`(Twelve Data API 전문 문서)에 있다. 여기는 `infra/price/` 폴더 전체에 적용되는 설계 결정만 담는다.

## 클라이언트별로 독립 구현한다

`UpbitPriceClient`/`ExchangeRateClient`/`StockPriceClient`/`TwelveDataClient`를 공통 인터페이스로 묶지 않았다. 응답 스키마·에러 케이스가 서로 달라 얕은 추상화가 되는 것을 피하기 위함이다. `domain/service/PriceService`가 `AssetType`(+STOCK은 통화)으로 분기해 라우팅만 담당하고, 도메인 값 객체 `Price`를 반환한다(웹 계층 비의존). 새 시세 클라이언트를 추가할 때도 이 패턴을 그대로 따를 것 — 억지로 공통 인터페이스를 만들지 않는다.

**티커 매칭 실패의 HTTP 표현이 벤더마다 다르다** — 이 프로젝트에서 반복적으로 실측이 필요했던 함정이다: 업비트는 정상 200 + 빈 배열, 공공데이터포털은 정상 200이지만 인증 실패 시 완전히 다른 루트 스키마(`OpenAPI_ServiceResponse`), Twelve Data는 존재하지 않는 심볼·인증 실패 모두 **정상적인 HTTP 4xx**(404/401, `{"code":...,"status":"error"}` 형태)로 온다(Task 025 실측, 2026-09-08). 새 클라이언트를 추가할 때 "매칭 실패가 200으로 오는지 4xx로 오는지"를 절대 문서만 보고 가정하지 말고 실제 키로 재현해 확인할 것.

## STOCK 시세는 EOD(전일 종가) 데이터다 (공공데이터포털/KRW)

`Price.asOf`는 API 응답의 기준일자(`basDt`)를 `Asia/Seoul` 자정 `Instant`로 변환한 값을 쓴다. `Instant.now()`를 쓰면 실시간 시세처럼 사용자를 오도하기 때문이다.

## STOCK+USD(Twelve Data) 시세는 장중엔 실시간에 가깝지만 휴장일엔 마지막 정규장 종가다

`TwelveDataClient`(Task 025)는 공공데이터포털과 통화만 다른 게 아니라 데이터 신선도 성격도 다르다 —
실제 API 키로 curl 검증한 결과(2026-09-08, 휴장일 직후), `/quote` 응답의 `datetime`은 시각 없이
날짜만("2026-09-04") 오고 `close`는 마지막 정규장 종가를 그대로 반환한다. 반면 `last_quote_at`(Unix
epoch, 초)은 그 거래일의 실제 마지막 체결 시각(정규장 마감 무렵)을 담고 있어 더 정밀하다 — `Price.asOf`는
`Instant.now()`가 아니라 이 필드로 채운다. `Instant.now()`를 쓰면 주말·공휴일에 오래된 종가를 "방금
갱신된 시세"처럼 보이게 만들어 EOD 데이터를 실시간처럼 오도하는 것과 동일한 함정에 빠진다 — 위
공공데이터포털 원칙("Instant.now()를 쓰면 실시간 시세처럼 오도")이 여기도 그대로 적용되지만, 근거
필드가 `basDt`(날짜)가 아니라 `last_quote_at`(초 단위 타임스탬프)이라는 점이 다르다.

## 코인(COIN) 티커·마켓 코드·통화 규칙

사용자는 자산 등록 화면에서 코인 심볼만 입력한다("BTC"). `UpbitPriceClient.getPrice(ticker, currency)`가
자산의 `currency`로 업비트 마켓 코드를 조립한다 — KRW는 `KRW-BTC`, USD는 `USDT-BTC`로 매핑한다.
**업비트는 `USD-BTC` 같은 달러 마켓 자체를 지원하지 않는다**(원화 마켓과 스테이블코인 마켓만 존재) — USDT는
사실상 1달러에 고정된 스테이블코인이라는 전제로 USD의 대체 마켓으로 근사했다(2026-09-07 사용자 확정).

USDT 마켓에서 받은 값은 달러 표시 시세이므로, `PriceService.coinPrice()`가 CASH(USD)와 동일한
`ExchangeRateClient`로 원화 환산까지 마친 뒤에야 `Price`를 반환한다 — 환산을 빼먹으면 `evaluationKrw`에
달러 숫자가 원화인 것처럼 그대로 노출된다(실측 회귀 사례: `PriceServiceTest.coinUsdAssetConvertsUsdtQuoteToKrwUsingExchangeRate`).
이 환율 조회는 `rate:USD:KRW` 전용 캐시(freshTtl은 CASH(USD)와 동일한 12시간)를 쓴다 — 그렇지
않으면 COIN의 짧은 freshTtl(10초) 주기로 환율 API가 불필요하게 반복 호출된다. **CASH(USD) 자산
자신의 캐시 키(`price:CASH:USD`)와는 절대 공유하지 않는다** — 과거에 공유했다가, CASH(USD) 조회가
KRW 스케일(0자리)로 반올림한 값을 그 키에 먼저 채워두면 COIN/STOCK(USD) 환산이 반올림된 값을
원본 환율로 착각해 재사용하는 결함이 있었다(실측: 51.85달러를 반올림값 1350원으로 계산하면
69,998원, 원본 1350.05원으로 계산하면 70,000원 — 캐시 population 순서에 따라 결과가 달라지는
비결정적 결함). `PriceService.cachedUsdKrwRate()`가 이 전용 키를 관리하며, STOCK+USD(Twelve
Data) 환산도 동일 캐시를 공유한다.

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

`serviceKey`/`apiKey`처럼 비밀값이 필요한 클라이언트는 `${ALLFOLIO_STOCK_SERVICE_KEY:}` 형태로
환경변수에 위임한다. 코드·설정 파일에 하드코딩 금지.

**`JwtProperties`와 동일 패턴이 아니다** — `JwtProperties.secret`은 약한 키가 조용히 쓰이는 것을
막기 위해 `@NotBlank`로 미설정 시 부팅 실패를 의도한다. 반면 `StockProperties.serviceKey`/
`TwelveDataProperties.apiKey`는 정반대 정책이다 — 외부 시세 API 키가 없어도 앱 자체는 정상
부팅돼야 하고, 해당 자산 타입의 시세 조회만 실패해야 한다(루트 CLAUDE.md 확정 정책). 그래서 이
두 필드에는 검증 애너테이션을 붙이지 않는다 — `@Validated` + `@ConfigurationProperties` 조합은
바인딩 시점에 실제로 Bean Validation을 수행하므로, `@NotBlank`를 붙이면 빈 문자열 바인딩 시
`ConfigurationPropertiesBindException`으로 컨텍스트 로딩 자체가 실패한다(과거 `StockProperties`
결함 실측 — 문서와 실제 동작이 어긋났던 사례). 새 외부 시세 클라이언트를 추가할 때도 API
키 필드에는 검증 애너테이션을 붙이지 말 것.
