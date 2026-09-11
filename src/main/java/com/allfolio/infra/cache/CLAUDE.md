# Redis 캐시·Throttle 규칙 (Task 022)

`PriceCacheStore`(시세 캐시)와 `PriceThrottle`(요청 제한)이 이 폴더에 있다. 둘 다 `domain/service/PriceService`가 오케스트레이션하며, 시세 클라이언트 자체(`infra/price/`)와는 역할이 분리돼 있다.

## Throttle은 단건 조회 엔드포인트에만 적용된다

`GET /v1/assets/{id}/price`(단건)에만 사용자당 초당 1건 한도를 적용하고, `GET /v1/portfolio`(다건, 보유 자산 수만큼 시세를 한 번에 조회)에는 적용하지 않는다(사용자 확정 정책, Task 023에서 확정). 기존 한도를 포트폴리오 조회에 그대로 적용하면 자산이 2개 이상인 사용자는 매번 429를 겪기 때문이다. `PriceService`는 이 둘을 `resolve(..., enforceThrottle)` private 헬퍼로 공유하고 Throttle 소모 여부만 플래그로 가른다 — 새 진입점을 추가할 때도 이 분기 기준을 따를 것.

**캐시 히트는 Throttle 한도를 소모하지 않는다** — 캐시 미스·스테일 상태에서 외부 API를 실제로 호출할 때만 소모된다.

## INCR+EXPIRE는 Lua Script로 원자적으로 묶는다

Redis(Lettuce)에는 `INCR`과 `EXPIRE`를 한 번에 묶는 원자적 명령이 없다(실측 확인 — `INCREX` 같은 명령은 존재하지 않음). `PriceThrottle`은 `RedisScript`(Lua)로 두 연산을 원자적으로 처리한다. 이 스크립트는 `static final`로 캐싱해서 재사용할 것.

## 자산 유형별 캐시 TTL이 다르다

`application.yml`의 `price-cache.*`(COIN 10초 / STOCK·CASH-USD 12시간 / 스테일 상한 24시간)를 따른다. COIN은 변동성이 커서 TTL이 짧고, STOCK·환율은 하루 단위로 갱신되는 데이터라 TTL이 길다.

## 조회 실패도 네거티브 캐싱한다

존재하지 않는 티커 등으로 시세 조회 자체가 실패한 경우도 `price-cache.negative-ttl`(30초) 동안 캐싱한다. 너무 짧으면 남용 방지 효과가 없고, 너무 길면 티커가 나중에 정상 등록돼도 한동안 계속 실패로 응답하게 되는 트레이드오프를 감안한 값이다.

## 스테일 폴백은 에러가 아니라 206이다

외부 API 장애 시 캐시에 스테일(만료됐지만 `stale-ceiling` 이내) 값이 있으면 503 대신 **206 + 응답 본문 `isStale:true`**로 응답한다. 캐시에 값 자체가 없을 때만 503 `EXTERNAL_API_DOWN`(`infra/price/` 소관 에러 경로)으로 빠진다.

## SearchThrottle — PriceThrottle과 별도 컴포넌트 (Task 026)

`SearchThrottle`(@Component)은 `PriceThrottle`과 동일한 Lua INCR+PEXPIRE 스크립트를 쓰지만 **완전히 독립된
컴포넌트**다. Redis 키는 `throttle:search:{userId}`로 분리하고, `SearchThrottleProperties`(limit=60/window=10s,
`allfolio.search-throttle.*`)를 직접 주입받는다.

**60건/10초인 이유:** 프론트 디바운스(350ms) 1회 발동마다 `GET /v1/assets/search`를 KRW/USD로 나눠
병렬 2건 호출한다(`SearchCombobox.runSearch` — currency가 필수 파라미터라 프론트가 나눠 호출). userId
단위 카운터는 currency와 무관하게 공유되므로 "디바운스 1회 = 요청 2건"이다. 350ms 간격으로 쉬지 않고
10초간 계속 타이핑하는 극단적 케이스는 최대 28회 발동×2건=56건이라, "디바운스 1회=1건"을 가정했던
기존 30건/10s(15회·5.25초 만에 429)로는 부족해 60건/10s로 상향했다(Task 027 이월 갭 수정).

**분리 이유:** `PriceThrottle`에 `keyPrefix` 인자만 바꿔 재사용하면, 내부에서 `PriceThrottleProperties`(1건/1s)가
실제 적용돼 SearchThrottleProperties를 주입했어도 무시되는 함정이 있다. 자동완성 시나리오에서 "초당 1건" 한도는
두 글자만 쳐도 429가 나는 UX 결함이 되므로 별도 컴포넌트로 분리해 60건/10s 한도를 독립 보장한다.

`GET /v1/assets/{id}/price`(PriceThrottle, 1건/1s)와 `GET /v1/assets/search`(SearchThrottle, 60건/10s)는
서로 한도를 공유하지 않는다.

## SearchCacheStore — 종목 검색 결과 캐시 (Task 026)

`SearchCacheStore`는 `PriceCacheStore`와 다르게 fresh/stale 2단계 없이 단일 TTL(6h,
`allfolio.search-cache.ttl`)만 사용한다. 검색 결과는 실시간성 요구가 없고 종목 목록은 하루 수준으로
안정적이기 때문이다.

**COIN 캐시 전략:** `UpbitPriceClient.listMarkets()`가 반환하는 전체 마켓 목록을 `search:COIN:ALL` **단일 키**로
캐싱한다. q·currency 필터는 `SearchService.filterByQuery`에서 인-메모리로 수행한다. 이 전략의 이점:

- 캐시 키가 검색어 수에 비례해 폭발하지 않는다
- 첫 번째 사용자 요청만 업비트를 호출하고 이후 6시간은 Redis 히트

STOCK 검색은 `search:STOCK:{currency}:{q}` 키로 질의어 단위 캐싱한다(업비트와 달리 공공데이터포털·Twelve Data는
서버 측 필터링을 API가 담당하므로 전체 목록 캐싱이 의미 없다).
