# loadtest — Task 031 서브태스크 1 (loadtest 인프라 구축 및 베이스라인 측정)

Docker로 실행하는 k6 스크립트 4종 + 실측 결과(`results.md`). 새 부하 시나리오를 추가할 때도 이
디렉터리 구조를 유지한다 — Gradle 빌드 대상이 아닌 순수 스크립트 모음이라 `src/` 밖에 둔다.

**경고 — 로컬 전용, 운영 환경에 절대 실행하지 말 것.** 이 디렉터리의 모든 k6 스크립트는 `setup()`에서
실제 회원가입(`POST /v1/auth/signup`)으로 진짜 계정을 만들고 비밀번호를 스크립트에 하드코딩한다.
`BASE_URL` 환경변수만 바꾸면 그 값이 가리키는 어떤 환경에도(운영 포함) 계정을 생성하고 부하를
건다 — 로컬 개발 DB를 대상으로 한다는 전제가 이 기본값(`http://host.docker.internal:8080`)에만
있을 뿐, 스크립트 자체가 이를 강제하지 않는다.

## 1. SSE 측정 기술 제약 조사 (가장 먼저 확인한 것)

**결론: 표준 k6(공식 `grafana/k6` 이미지, 이 세션 기준 v2.2.0)는 EventSource/SSE 프로토콜을
지원하지 않는다.**

k6 공식 문서가 한때 `k6/experimental/sse` 모듈을 실험적으로 제공했지만, 이 세션에서 실제로
확인한 결과 `grafana/k6:latest`(v2.2.0)의 "automatic extension resolution"(스크립트가 import하는
모듈을 보고 필요한 익스텐션을 자동으로 찾아 커스텀 바이너리를 빌드해주는 기능)이 다음 모듈명을
전부 "unknown dependency"로 거부했다(실측, 인터넷 연결 상태에서 시도):

```
k6/experimental/sse
k6/x/sse
k6/net/sse
k6/sse
```

즉 이 레지스트리에 SSE 관련 익스텐션이 등록돼 있지 않다 — `xk6-sse`류 커스텀 확장을 Go
툴체인으로 직접 빌드(`xk6 build --with github.com/.../xk6-sse`)해야 하는데, 이 샌드박스 환경에는
Go 툴체인이 없고 별도 빌드 인프라를 새로 구축하는 것은 "베이스라인 측정"이라는 이번 서브태스크
범위를 벗어난다고 판단해 시도하지 않았다.

### 대안: 표준 `http.get()`으로 근사 측정

SSE 스트림은 서버가 먼저 끊지 않는 한 계속 열려 있다(이 앱은 `SseEmitter(Long.MAX_VALUE)`로
무제한 타임아웃을 건다, `AssetController.streamCandles` 참고). 표준 k6의 `http.get()`은 HTTP
연결이 완전히 끝나거나(EOF) 지정한 `timeout`에 도달할 때까지 블로킹된다 — SSE는 정상 동작 중엔
절대 EOF가 오지 않으므로, **`http.get(url, {timeout: N초})`을 걸면 반드시 "request timeout"
(k6 `error_code: 1050`)으로 끝난다.** 이걸 실패로 보지 않고 **"그 N초 동안 연결이 끊기지 않고
살아있었다"는 근사 신호**로 쓴다:

- `res.timings.duration`이 요청한 `timeout`에 근접(예: 90% 이상)하면 → 연결이 그 시간 내내
  유지됐다(성공으로 카운트).
- `res.timings.duration`이 `timeout`보다 훨씬 짧으면(즉시 오류) → 연결 자체가 거부됐거나
  인증 실패 등 즉각적인 실패(실패로 카운트).

**부가 발견(실측): k6 `http.get()`은 타임아웃으로 끊겨도 그 순간까지 수신한 바디를 그대로
돌려준다.** 즉 SSE 이벤트가 부분적으로라도 도착했다면 `res.body`에 `":heartbeat"`,
`"event:candle"`, `"id:<epoch ms>"` 같은 텍스트가 그대로 남아있다 — 이를 이용해 연결 유지
여부뿐 아니라 **실제 이벤트 수신 여부·전송 시각(`id:` 필드)까지 표준 k6만으로 확인**할 수 있었다
(`sse-probe` 실측, 아래 §3 참고). 그래서 이번 4개 스크립트 모두 표준 k6만으로 작성했고, 별도
curl 기반 보조 스크립트는 필요하지 않았다.

### Docker Desktop(macOS) 네트워킹 주의

`docker run --network=host`는 macOS의 Docker Desktop에서 기대대로 동작하지 않는다(Docker
Desktop은 Linux VM 위에서 도는 구조라 "host" 네임스페이스 공유 자체가 성립하지 않음, 실측:
`--network=host`로 실행하면 컨테이너 안에서 `localhost:8080`이 "connection refused"로 즉시
거부됨). **`http://host.docker.internal:8080`을 써야 macOS 호스트에서 기동한 앱에 도달한다**
(Docker Desktop이 기본 제공하는 DNS 별칭, 실측 확인). 이 디렉터리의 모든 스크립트는
`BASE_URL` 환경변수 기본값을 `http://host.docker.internal:8080`으로 잡아뒀다.

## 2. 실행 방법

사전 준비(모든 시나리오 공통):

```bash
docker compose up -d          # Postgres/Redis
ALLFOLIO_JWT_SECRET=$(openssl rand -base64 32) ./gradlew bootRun   # 별도 터미널/백그라운드
curl http://localhost:8080/actuator/health   # {"status":"UP"} 확인
```

각 스크립트는 `setup()`에서 자체적으로 회원가입(`POST /v1/auth/signup`, 인원 제한 없음)까지
하므로 별도 테스트 계정을 미리 만들 필요는 없다(단, `stock-candles.js`는 예외 — §2-3 참고).

### 2-1. SSE 브로드캐스트 (단일 구독 키, 최대 1,000 커넥션)

```bash
docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e TARGET_VUS=1000 -e RAMP_TIME=90s -e HOLD_TIME=30s -e RAMPDOWN_TIME=15s -e CONN_HOLD=20 \
  -v $(pwd)/loadtest:/scripts grafana/k6 run /scripts/sse-broadcast.js
```

### 2-2. SSE 다중 구독 키 (CandlePushScheduler 순회 성능)

```bash
docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e FILLER_VUS=499 -e RAMP_TIME=45s -e HOLD_TIME=200s -e RAMPDOWN_TIME=15s \
  -e MARKER_HOLD=270 -e FILLER_HOLD=250 \
  -v $(pwd)/loadtest:/scripts grafana/k6 run /scripts/sse-multi-key.js
```

**주의(실측 발견, 아래 결과에도 기록) — 구독 키가 앱을 재시작할 때까지 누적된다.** k6 컨테이너를
`--rm`으로 강제 종료해도(정상 종료가 아니라 타임아웃으로 클라이언트가 소켓을 닫는 경우)
`CandleSseRegistry`가 그 구독을 즉시 정리하지 못하는 경우가 실측 재현됐다 — 이전 실행에서 만든
`FAKE0000`류 구독 키가 앱을 재시작하기 전까지 다음 실행에도 그대로 남아 폴링 대상에 계속
포함된다. **동일 스크립트를 반복 실행해 "N개일 때"를 정확히 측정하려면 매번 앱을 재시작해서
`CandleSseRegistry`(인메모리 `ConcurrentHashMap`)를 비워야 한다.** 이 누적 자체가 Task 031의
다음 판단 태스크에 넘길 만한 별도 발견이라 `results.md`에 따로 기록했다.

### 2-3. STOCK 캔들 (캐시 히트)

`ALLFOLIO_STOCK_SERVICE_KEY` 없이는 캐시가 채워지지 않는다(첫 호출이 503으로 실패). 이 키가
없는 환경에서 캐시 히트 경로를 측정하려면, `CandleCacheStore`에 직접 시딩해야 한다 — 이번
세션에서는 임시 JUnit 테스트(`@SpringBootTest`, 실행 후 삭제)로 10년치 평일 일봉(2,609건)을
`candle:STOCK:LOADTEST` 키에 심었다. 재현하려면:

```java
// src/test/java/com/allfolio/ 아래 임시 배치, 실행 후 삭제
@SpringBootTest
class SeedCandleCacheTest {
    @Autowired CandleCacheStore candleCacheStore;
    @DynamicPropertySource static void jwt(DynamicPropertyRegistry r) {
        r.add("allfolio.jwt.secret", () -> "seed-secret-at-least-32-bytes-long");
    }
    @Test void seed() {
        // LocalDate.now().minusYears(10) ~ now, 평일만 DailyBar 생성 후
        candleCacheStore.save("candle:STOCK:<TICKER>", entry, Duration.ofHours(12));
    }
}
```

그 뒤 그 캐시 키에 대응하는 STOCK 자산(같은 `ticker`, KRW면 통화 접미사 없음)을 만들어 쓴다.
이미 캐시가 채워진 자산을 재사용하려면 그 자산을 소유한 계정 자격증명을 넘겨야 한다(타 유저
소유 자산은 404 — ID 유출 방지 컨벤션):

```bash
docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e EMAIL=<캐시를 채운 자산의 소유자 이메일> -e PASSWORD=<비밀번호> \
  -e ASSET_ID=<STOCK 자산 UUID> \
  -e VUS=10 -e DURATION=20s \
  -v $(pwd)/loadtest:/scripts grafana/k6 run --summary-trend-stats="avg,min,med,p(50),p(90),p(95),p(99),max" /scripts/stock-candles.js
```

`ASSET_ID`/`EMAIL`/`PASSWORD`를 생략하면 매번 새 계정 + 새 STOCK 자산을 만든다 — 이 경우
`ALLFOLIO_STOCK_SERVICE_KEY`가 설정돼 있어야 캐시가 채워져 의미 있는 측정이 된다.

### 2-4. COIN 캔들 (캐시 없는 패스스루)

```bash
docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e VUS=30 -e DURATION=30s -e TICKER=BTC \
  -v $(pwd)/loadtest:/scripts grafana/k6 run /scripts/coin-candles.js
```

`TICKER`는 업비트가 실제로 다루는 코드여야 한다(예: `BTC` → 내부적으로 `KRW-BTC` 마켓으로
정규화됨, `UpbitPriceClient.normalizeMarket`). 이 시나리오는 **의도적으로 실제 업비트 API에
부하를 준다** — `results.md`에 기록된 대로 30 VUs·30초만으로도 업비트가 실제 429를 반환하기
시작하므로, 오래 반복 실행하지 않는다.

## 3. k6 SSE 근사 방식 실측 근거(sse-probe)

이 세션에서 직접 확인한 원시 관찰(스크립트 자체는 일회성이라 저장하지 않음, 아래 결과만 기록):

```js
const res = http.get(url, { timeout: '35s' });
// → { status: 0, error: "request timeout", error_code: 1050,
//     duration_ms: 35000.12, body_len: 12, body_sample: ":heartbeat\n\n" }
```

- `duration_ms`가 요청한 timeout(35000ms)과 거의 일치 → 연결이 35초 내내 유지됐다는 뜻.
- `body_sample`에 실제 heartbeat 코멘트가 그대로 담겨 있다 → 부분 수신 바디를 k6가 보존함을 확인.

이 두 사실이 `sse-broadcast.js`/`sse-multi-key.js`의 측정 방법(연결 유지율 = duration 근접도,
이벤트 수신 = body 내 패턴 매칭) 전체의 근거다.
