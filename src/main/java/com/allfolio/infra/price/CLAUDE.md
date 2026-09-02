# 외부 시세 클라이언트 규칙

이 API 자체의 상세 요청/응답 스키마는 `.claude/agents/stock-price-api.md`(공공데이터포털 주식시세 API 전문 문서)에 있다. 여기는 `infra/price/` 폴더 전체에 적용되는 설계 결정만 담는다.

## 클라이언트별로 독립 구현한다

`UpbitPriceClient`/`ExchangeRateClient`/`StockPriceClient`를 공통 인터페이스로 묶지 않았다. 응답 스키마·에러 케이스가 서로 달라 얕은 추상화가 되는 것을 피하기 위함이다. `domain/service/PriceService`가 `AssetType`으로 분기해 라우팅만 담당하고, 도메인 값 객체 `Price`를 반환한다(웹 계층 비의존). 새 시세 클라이언트를 추가할 때도 이 패턴을 그대로 따를 것 — 억지로 공통 인터페이스를 만들지 않는다.

## STOCK 시세는 EOD(전일 종가) 데이터다

`Price.asOf`는 API 응답의 기준일자(`basDt`)를 `Asia/Seoul` 자정 `Instant`로 변환한 값을 쓴다. `Instant.now()`를 쓰면 실시간 시세처럼 사용자를 오도하기 때문이다.

## 에러 매핑

- 외부 API 장애(반복 실패 → Circuit Breaker Open 포함): 503 `EXTERNAL_API_DOWN`
- 시세 개념이 없는 자산(CASH, 또는 벤더 미정 자산): 400 `PRICE_NOT_APPLICABLE`

## Resilience4j Circuit Breaker 파라미터

기본값 `minimumNumberOfCalls=100`으로는 회로가 열리는 데 100번 호출이 필요해 테스트가 비현실적이다. `application.yml`의 `resilience4j.circuitbreaker.instances.{upbit,exchange-rate,stock}`에 `slidingWindowSize=4` 등을 명시 설정해, 테스트를 가능하게 하는 동시에 실제 운영 튜닝까지 겸한다.

## 시크릿

`serviceKey`처럼 비밀값이 필요한 클라이언트는 `${ALLFOLIO_STOCK_SERVICE_KEY:}` 형태로 환경변수에 위임한다(`JwtProperties`와 동일 패턴). 코드·설정 파일에 하드코딩 금지.
