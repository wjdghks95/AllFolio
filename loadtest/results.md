# 부하 테스트 베이스라인 결과 — Task 031 서브태스크 1

측정 환경: 로컬 macOS(Apple Silicon), k6는 Docker(`grafana/k6:latest`, v2.2.0)로 실행, 앱은
`./gradlew bootRun`으로 같은 머신에서 직접 기동(Virtual Threads 활성, `spring.threads.virtual.enabled=true`).
k6 클라이언트와 서버가 같은 물리 머신을 공유하므로 **네트워크 지연은 사실상 0에 가깝다** — 이
결과는 "앱 자체의 동시성 처리 한계"를 보는 데는 유효하지만, 실제 배포 환경(클라이언트-서버 간
실제 네트워크 왕복)의 절대 지연치로 그대로 쓸 수는 없다. `ALLFOLIO_STOCK_SERVICE_KEY`/
`ALLFOLIO_TWELVEDATA_API_KEY`는 샌드박스 권한 제약으로 이 세션에서 앱 프로세스에 로드하지
못했다(아래 시나리오 3 참고) — COIN(업비트) 쪽은 키가 필요 없어 전부 실제 업비트 API로 측정했다.

## 요약 (다음 판단 태스크에 필요한 핵심 수치)

| 질문 | 답 |
|---|---|
| SSE 단일 구독 키에 몇 개 커넥션까지 붙일 수 있나 | **최소 1,000개 확인 — 100% 연결 유지, 거부/실패 0건** |
| CandlePushScheduler가 10초 폴링 틱 안에 끝나나 | **아니다(병렬화 전).** N=6(정상)→10.1초, **N=100→평균 34초(최대 46초)**, **N=500→평균 62초(최대 72초)** — 이미 N=100에서 목표(10초)의 3배 이상. **서브태스크 2에서 키 단위 병렬화로 해결** — N=100→9.7초, N=500→10.7초로 목표치 근접(하단 「Task 031 서브태스크 2」 참고) |
| STOCK 캔들(최대 2,450건) 캐시 히트 응답 크기·지연 | 232,234 bytes, P50 13.3ms · P99 20.6ms(10 VUs 기준) |
| COIN 캔들이 실제로 업비트를 남용하는가 | **그렇다.** 30 VUs·30초만으로 업비트가 실제 429를 반환하기 시작했고, 우리 서버 응답의 98%가 503로 저하됨 |
| STOCK candles에 `limit` 파라미터를 도입해야 하는가 | **아니다.** 응답 크기는 10년 캡·분봉 미지원으로 사실상 고정 상한(232KB)이고, 실측 결과 HTTP 압축(gzip)이 현재 꺼져 있어(`Content-Encoding` 헤더 미부여 확인) 이것이 켜지면 약 28.5KB로 줄어든다 — `limit`보다 압축이 근본 해결책(하단 「Task 031 서브태스크 3」 참고, 코드 변경 없음) |

---

## 시나리오 1 — SSE 브로드캐스트(단일 구독 키, `sse-broadcast.js`)

**실행:** `TARGET_VUS=1000 RAMP_TIME=90s HOLD_TIME=30s RAMPDOWN_TIME=15s CONN_HOLD=20`
(BTC/KRW/day 1개 키에 최대 1,000개 emitter 동시 접속)

| 지표 | 값 |
|---|---|
| 최대 동시 VUs(=동시 SSE 커넥션) | **1,000** (`vus_max`) |
| 연결 유지율(`sse_connection_held_rate`) | **100.00%** (4,140 / 4,140) |
| 연결 유지 시간(요청 timeout=20s 대비) | avg 20000.2ms / min 19955.1ms / max 20015.0ms / p95 20003.7ms — 요청한 20초와 사실상 정확히 일치 |
| 완료된 반복(iterations) | 4,140 complete / 476 interrupted(램프다운 중 강제 종료, 실패 아님) |
| 수신한 candle 이벤트(전체 커넥션 합산) | 1,519건 (10.51/s) |
| 수신한 heartbeat(전체 커넥션 합산) | 2,845건 (19.69/s) |
| 총 소요 시간 | 2분 24.5초 |

**해석:** 커넥션 수용 자체(Tomcat/Virtual Thread 계층)는 1,000개에서 전혀 병목이 없었다 —
거부·타임아웃 전 실패 0건. `http_req_failed`가 99.92%로 찍히는 것은 k6가 모든 SSE 연결을
"timeout"으로 분류하기 때문(README §1 참고)이며 실제 실패가 아니다 — `sse_connection_held_rate`가
이 시나리오의 진짜 성공 지표다.

## 시나리오 2 — SSE 다중 구독 키(`sse-multi-key.js`, CandlePushScheduler 순회 성능)

**중요 실측 발견 — 구독 키가 앱 재시작 전까지 누적된다.** 첫 실행(FILLER_VUS=199)을 끝내고 k6
컨테이너가 완전히 종료된 뒤(`docker ps`로 잔여 컨테이너 없음 확인) **40초 넘게 기다린 뒤에도**
앱 로그에 `FAKE0046`, `FAKE0029` 등 이미 종료된 연결의 구독 키를 계속 폴링하는 로그가 남아있었다
(`CandlePushScheduler`가 매 틱 `TickerNotFoundException`을 던지며 실패 처리). 이는
`CandleSseRegistry`가 emitter의 `onError`/`onCompletion` 콜백으로 정리되도록 설계돼 있지만,
클라이언트가 타임아웃으로 소켓을 끊는 상황(정상 종료가 아닌 경우)에서 그 콜백이 즉시(또는 전혀)
발화하지 않는 사례가 있다는 뜻이다 — **다음 판단 태스크에 반드시 넘길 별도 이슈**로 기록한다
(이번 서브태스크는 측정만 하고 원인 진단·수정은 하지 않았다). 이 누적 때문에 "N개일 때"를
정확히 측정하려면 **매 실행 전 앱을 재시작**해 인메모리 레지스트리를 비워야 했다 — 아래 결과는
모두 재시작 직후의 "깨끗한" 상태에서 측정했다.

| N(총 구독 키 수, marker 1개 포함) | marker candle 이벤트 간 간격(gap) | 비고 |
|---|---|---|
| 6 (필러 5 + marker 1) | **10,133ms** (단일 샘플) | 목표(10초)와 거의 정확히 일치 — 정상 |
| 100 (필러 99 + marker 1) | avg **33,940ms** / min 21,499 / max 46,382 / p95 45,138 (샘플 2개) | 목표의 3.4배 |
| 500 (필러 499 + marker 1) | avg **62,118ms** / min 49,761 / max 71,714 / p95 71,710 (샘플 4개) | 목표의 6.2배 |

**필러 커넥션 자체는 전부 정상 유지됐다** (N=500에서 `sse_filler_connection_duration_ms` avg
250,006ms ≈ 요청한 250초 그대로, 100% 연결 유지 — 시나리오 1과 마찬가지로 커넥션 수용 자체는
병목이 아님을 재확인). 병목은 순전히 **CandlePushScheduler.pollAndPush()의 순차 for-loop가
구독 키 하나마다 업비트에 블로킹 HTTP 호출을 보내는 구조**에 있다 — N이 늘수록 한 틱을 도는 데
걸리는 시간이 그대로 늘어난다.

**N=1,000까지 실측하지 못한 이유:** N=100→500 구간에서 이미 6배 넘게 지연이 벌어지는 추세가
명확했고, N=500 한 번 측정에 약 4분 30초가 걸렸다(마커가 gap을 4개 모으려면 관찰 창을 그만큼
길게 잡아야 함). 이 추세선상 N=1,000이면 한 틱이 최소 70초~그 이상 걸릴 것으로 추정되나, 이는
**외삽(extrapolation)이며 실측치가 아니다** — 정확한 절대치가 필요하면 다음 판단 태스크에서
`FILLER_VUS=999`로 이 스크립트를 그대로 재실행하면 된다(스크립트 자체는 1,000까지 지원하도록
작성돼 있다, `sse-multi-key.js` 참고).

## 시나리오 3 — `GET /v1/assets/{id}/candles` STOCK(캐시 히트, `stock-candles.js`)

`ALLFOLIO_STOCK_SERVICE_KEY`를 이 세션의 샌드박스 권한 제약으로 앱 프로세스에 로드하지 못해
(`.env`를 `source`하는 Bash 명령이 권한 시스템의 deny rule에 막힘 — 코디네이터 지시대로 억지로
우회하지 않았다), 실제 공공데이터포털 호출로 캐시를 채우는 콜드 패스는 이번에 측정하지 못했다.
대신 **캐시 히트 경로**(이번 측정의 실제 대상)는 `CandleCacheStore`에 직접 시딩해 측정했다 —
10년치 평일 일봉(2,609건)을 `candle:STOCK:LOADTEST` Redis 키에 저장한 뒤(README §2-3의 임시
JUnit 테스트로 1회 시딩, 실행 후 파일 삭제), 그 자산으로 반복 호출했다. 이 경로는 벤더 키와
무관하게 `CandleService.ensureCoverage()`가 캐시 히트를 확인하는 순간 곧장 반환하므로, 측정
대상인 "캐시 히트 상태의 캔들 조회 자체" 성능은 벤더 키 유무와 무관하게 정확하다.

**실행:** `VUS=10, DURATION=15s` (`--summary-trend-stats`로 p50/p99 명시 확보)

| 지표 | 값 |
|---|---|
| 응답 크기 | **232,234 bytes**(2,609개 bar, 10년치 일봉) — 고정(캐시 데이터 동일) |
| 지연 P50 | **13.32ms** |
| 지연 P90 | 16.59ms |
| 지연 P95 | 17.82ms |
| 지연 P99 | **20.55ms** |
| 지연 max | 96.82ms |
| 성공률 | 100%(8,076/8,076), 실패 0건 |
| 처리량 | 534 req/s (10 VUs) |

**해석:** 캐시 히트 상태에서는 전혀 문제가 없다 — P99 20ms대는 매우 빠르다. ROADMAP이 우려한
지점(m6: 페이지 크기 제한 없이 캐시 전량을 한 응답으로 반환)의 실제 위험은 지연이 아니라
**응답 크기**(10년치 일봉 기준 232KB, 종목 상장 기간이 더 길거나 분봉이 섞이면 더 커질 수 있는
구조)와 **네트워크가 느린 모바일 클라이언트에서의 체감 지연**이다 — 이번 로컬 측정(같은 머신,
네트워크 지연 0)은 그 부분을 드러내지 못하므로, `limit` 파라미터 도입 여부는 이 지연 수치만으로
판단하지 말 것을 다음 판단 태스크에 남긴다.

## 시나리오 4 — `GET /v1/assets/{id}/candles` COIN(캐시 없는 패스스루, `coin-candles.js`)

**실행:** `VUS=30, DURATION=30s, TICKER=BTC` (실제 업비트 `KRW-BTC` 마켓, `minute1` 캔들)

| 지표 | 값 |
|---|---|
| 총 요청 수 | 3,328건 (32.6초간, 102 req/s) |
| 200 성공 | **30건 (0.9%)** |
| 503(우리 서버 응답) | **3,260건 (98.0%)** |
| 기타(체크 실패, 응답 0바이트 포함) | 35건 |
| 업비트 실제 429 응답 | **실측 확인** — 앱 로그에 `https://api.upbit.com:443 responded with status 429; request will be automatically re-executed in 1 SECONDS` 반복 기록 |
| Resilience4j `upbit` CB 상태 | 테스트 직후 **`half_open`**(테스트 시작 전엔 `closed`) |
| CB 호출 통계(테스트 구간) | successful 35 / failed 20 / ignored 2,117 (Resilience4j 표준 라벨 그대로 기록 — "ignored"는 `ignore-exceptions` 설정에 해당하는 예외로 집계된 호출) |

**해석:** `GET /v1/assets/{id}/price`(1건/초 Throttle 적용)와 달리 이 경로는 클라이언트 요청
동시성이 그대로 업비트 호출 동시성으로 이어진다 — **불과 30명의 동시 사용자만으로 업비트의
실제 rate limit(REST 30 req/s)을 넘겨버렸고**, 그 결과 CB가 열리기 직전 상태(half_open)까지
갔으며 우리 서버 응답의 98%가 503으로 저하됐다. ROADMAP이 우려한 "`GET /price`와의 비대칭이
실제 남용으로 이어지는지"에 대한 답은 **명확히 그렇다** — Throttle 도입이 필요해 보인다는
근거로 다음 판단 태스크에 넘긴다.

---

## Task 031 서브태스크 2 — CandlePushScheduler 병렬화 Before/After

서브태스크 1의 실측(위 시나리오 2)에서 확인된 지연 원인은 `CandlePushScheduler.pollAndPush()`가
`registry.activeKeys()`를 **순차 for-loop**로 돌며 구독 키 하나마다 업비트에 블로킹 HTTP 호출을
보내는 구조였다. 재판단 없이 바로 병렬화를 구현했다 — 이미 같은 클래스의 `sendAsync`/
`sendHeartbeatAsync`가 쓰던 `Thread.ofVirtual().start(MdcPropagation.wrap(...))` 패턴을 키 단위
처리(`pushIfChanged`)에도 그대로 적용했다(고정 스레드풀 신규 도입 금지 원칙 준수, Virtual
Thread 기반 유지).

**재측정 조건**: 서브태스크 1과 같은 환경(로컬 macOS, k6는 Docker, 앱은 `./gradlew bootRun`
직접 기동)에서, 같은 스크립트(`sse-multi-key.js`)를 **매 실행 전 앱을 재시작**해 `CandleSseRegistry`
누적을 회피한 뒤 N=100/N=500 두 지점을 재측정했다.

| N(총 구독 키 수) | Before(순차, 서브태스크 1) | After(병렬화, 이번 서브태스크) | 개선 |
|---|---|---|---|
| 100 | avg **33,940ms** (min 21,499 / max 46,382, 샘플 2개) | **9,676ms**(샘플 1개) | 목표(10초) 근접 — 3.5배 단축 |
| 500 | avg **62,118ms** (min 49,761 / max 71,714, 샘플 4개) | **10,734ms**(샘플 1개) | 목표(10초) 근접 — 5.8배 단축 |

**해석:** 병렬화 이후 N=100/N=500 모두 목표 폴링 주기(10초)에 근접한 값으로 돌아왔다 —
순차 구조에서 N에 비례해 늘어나던 한 틱 소요 시간이, 병렬화 이후에는 N과 거의 무관하게 유지됨을
실측으로 확인했다. 필러 커넥션 유지율은 이번에도 100%에 가까웠다(`sse_filler_connection_duration_ms`
N=100 avg 249,992ms / N=500 avg 249,997ms, 둘 다 요청한 250초에 거의 정확히 일치) — 커넥션 수용
자체는 이번에도 병목이 아니었다.

**표본 수 관련 유의사항**: 이번 재측정은 각 N당 gap 샘플이 1개뿐이다(Before는 N=100에서 2개,
N=500에서 4개). marker VU 하나가 재접속 없이 계속 열려 있는 구조라 관찰 창(`MARKER_HOLD=270s`)
안에서 candle 값이 실제로 바뀐 횟수만큼만 gap이 잡히는데, 이번 세션에서는 업비트 BTC/KRW 일봉
종가가 관찰 창 동안 한 번만 바뀌었다(candle_push_count=2 → gap 1개). 값 자체가 목표치(10초)에
매우 근접하고 Before/After 격차가 6배 안팎이라 결론(병렬화로 지연이 해소됨)을 뒤집을 정도는
아니라고 판단했으나, 정밀한 분포(p95 등)가 필요하면 `MARKER_HOLD`를 더 길게 잡아 재실행할 것.

**별도 발견(이번 태스크 범위 밖, 원인 진단·수정 안 함) — `CandleSseRegistry` 구독 키 누적 버그**:
서브태스크 1에서 이미 발견된 대로, k6 클라이언트가 타임아웃으로 소켓을 끊을 때
`CandleSseRegistry`가 그 구독을 즉시 정리하지 못하는 사례가 이번 재측정에서도 재현됐다 — N=100
측정 후 N=500 측정을 위해 앱을 재시작하지 않았다면 이전 실행의 `FAKE*` 구독 키가 그대로 남아
정확한 N 측정이 불가능했을 것이다. 이번에도 매 실행 전 앱 재시작으로 우회했다. 이 버그 자체의
원인 진단·수정은 다음 최종 문서화 태스크로 넘긴다.

### 서브태스크 2 후속 — code-reviewer M1(Task 031 통합 검증) fire-and-forget 결함 수정 후 재측정

위에서 측정한 병렬화(`Thread.ofVirtual().start(...)`로 던지고 즉시 반환)에 code-reviewer가 실측으로
발견한 결함이 있었다 — `pollAndPush()`가 던진 스레드를 `join()`하지 않아 Spring `fixedDelay`의
"이전 실행 완료 후 N초" 보장이 깨졌다(업비트 응답 2.5초 지연 스텁 + 구독 키 1개로 20초 관찰 시
동시 in-flight 요청이 항상 3건 유지됨, 즉 3세대의 폴링 스레드가 같은 키를 동시에 처리 중이었다).
수정: 던진 가상 스레드를 `List<Thread>`에 모았다가 틱이 끝나기 전에 전부
`Thread.join(pollTickJoinTimeout)`으로 기다린다. `pollTickJoinTimeout`은 폴링 주기 자체
(`allfolio.price-cache.coin-fresh-ttl`, 기본 10초)와 동일하게 둔다 — 근거는
`CandlePushScheduler.pollAndPush()` Javadoc 참고(정상 케이스에서는 "이번 틱이 다음 틱 시작 전에
끝난다"가 실질적으로 항상 성립하고, 병리적으로 느린 응답이 있어도 세대 중첩을 최대 2세대로
제한한다).

**재측정 조건**: 앱을 재시작(레지스트리 초기화)한 뒤 `sse-multi-key.js`를 README §2-2 그대로의
명령(`FILLER_VUS=499 RAMP_TIME=45s HOLD_TIME=200s RAMPDOWN_TIME=15s MARKER_HOLD=270 FILLER_HOLD=250`)
으로 N=500 재실행했다.

**(a) 틱이 더 이상 겹치지 않는지 — 직접 확인.** k6의 `sse_marker_candle_push_count`(marker 1개)로는
이번 세션에서도 관찰 창 안에 BTC/KRW 일봉 종가가 거의 안 바뀌어 gap 표본을 얻지 못했다(서브태스크
2·이전 재측정과 동일한 한계). 대신 이번엔 더 직접적인 증거를 썼다 — 앱 로그(JSON 구조화 로그)에서
`pollAndPush()`가 매 틱 발급하는 traceId 단위로 "SSE 캔들 폴링 실패"(존재하지 않는 `FAKE*` 티커에
대한 `TickerNotFoundException`) 로그를 모아 틱별 시간 구간을 계산했다:

| 지표 | 값 |
|---|---|
| 관찰된 틱(traceId) 수 | 41개 |
| 틱 시작 간 간격(interval) | min **10.04s** / avg **10.14s** / max **11.61s** |
| "다음 틱 시작 − 이전 틱 종료" 간격(gap) | min **10.00s** / max **10.05s** — **41개 전 구간에서 항상 양수** |
| 겹치는 틱(gap < 0) 발견 개수 | **0건** |

**해석:** gap이 41개 구간 전부 양수(최소 10.00초 이상)라는 것은, 어떤 틱의 처리가 끝나기 전에
다음 틱이 시작한 사례가 이 재측정 전체에서 단 한 번도 없었다는 뜻이다 — code-reviewer가 재현한
"동시 in-flight 3건 유지" 패턴이 해소됐음을 실측으로 확인했다. 틱 간격이 정확히 10.00초가 아니라
10.04~11.61초로 약간 더 긴 것은 join 때문에 `pollAndPush()` 자체의 실행 시간(팬아웃한 500개 키
처리 중 가장 느린 것까지 포함)이 다음 `fixedDelay` 카운트에 더해지기 때문이며, 이는 Javadoc에
문서화한 의도된 트레이드오프다(가장 느린 틱도 join 타임아웃 10초를 넘기지 않았다 — 그렇지 않았다면
interval이 20초 근처로 튀었을 것이다).

**(b) 업비트 429·Resilience4j `upbit` CB 상태 — 이번 재측정에서는 정상(`closed`)이 아니었다.**
서브태스크 4가 Throttle로 억제한 대상은 `GET /v1/assets/{id}/candles`(REST, 사용자당 3건/1초)이지,
이 SSE 폴링 스케줄러의 업스트림 호출에는 별도 Throttle이 없다 — join 수정으로 "틱 겹침"은
해소됐지만, **한 틱 안에서 500개 구독 키를 동시에 팬아웃하는 것 자체**는 여전히 순간적으로 최대
500건의 동시 업비트 호출을 만든다. 이번 재측정에서 실제로:

| 지표 | 값 |
|---|---|
| 업비트 429(TooManyRequests) 실패 | **97건**(`resilience4j_circuitbreaker_calls_seconds_count{kind="failed",name="upbit"}`) |
| 업비트 404(TickerNotFoundException, `FAKE*` 필러 — 정상) | 301건(`kind="ignored"`, ignore-exceptions 설정대로 CB 실패 집계에서 제외됨) |
| 업비트 성공 | 1건(`kind="successful"`) |
| Resilience4j `upbit` CB가 open을 거쳤다는 증거 | `not_permitted_calls_total{name="upbit"}` **13,019건** — CB가 열린 동안 호출 자체가 업비트로 나가지 않고 즉시 거부됨 |
| 재측정 종료 시점 CB 상태 | **`half_open`**(closed가 아님 — open에서 회복 시도 중) |

**해석:** M1 수정(join)은 "세대 중첩"만 해소하도록 범위를 좁힌 것이었고, 실제로 그 목적은
달성했다(위 (a)). 하지만 이번 재측정으로 **별도의, 더 넓은 범위의 리스크**가 드러났다 — N=500
같은 대규모 동시 구독 상황에서는 한 틱 안의 팬아웃 자체가 업비트의 실제 rate limit(REST 30 req/s)을
크게 초과해 429를 유발하고 CB를 half_open까지 밀어붙인다(REST `GET /candles`용 서브태스크 4
`CandleThrottle`은 이 SSE 폴링 경로에는 적용되지 않는 별개 코드 경로다). **이 리스크는 M1(틱 경계
보존) 수정의 범위 밖이며 이번 태스크에서 코드로 대응하지 않는다** — join은 "틱이 겹치지 않게"
하는 것이 목적이었고, "한 틱 안에서 몇 개까지 동시에 팬아웃해도 되는가"는 SSE 폴링 스케줄러 자체의
동시성 상한(예: 세마포어로 팬아웃 동시성을 업비트 한도 이하로 제한하는 등)을 다루는 별도 판단
태스크로 넘긴다. N=500 동시 구독 키라는 조건 자체가 이번 로드테스트가 의도적으로 만든 극단
시나리오이므로, 실제 운영에서 이 정도 규모의 동시 COIN 구독이 현실적인지도 그 판단 태스크에서
함께 검토할 것.

---

## Task 031 서브태스크 3 — STOCK candles 무제한 응답 실측 판단(`limit` 파라미터 도입 여부)

시나리오 3(위)에서 이미 확인한 것: 캐시 히트 상태 STOCK candles(10년 KRW 일봉, 2,609건) 응답은
232,234 bytes, P99 20.55ms로 **로컬 측정 기준 지연은 전혀 문제가 없다**. 남겨둔 판단은 "그 지연
수치만으로 `limit` 도입 여부를 정하지 말라"는 것이었다 — 진짜 우려는 응답 크기 자체와 모바일
네트워크 체감 지연이었다. 이번 서브태스크에서 그 두 가지를 직접 실측했다.

### 실측 1 — 응답 크기 상한이 사실상 고정값이라는 점

- STOCK은 분봉을 지원하지 않는다(`CandleService.getCandles`가 `interval.isMinute()`이면 400으로
  차단) — 세분화는 일/주/월/년봉뿐이다.
- `application.yml`의 `candle-cache.max-history-years: 10`으로 일봉 캐시 자체가 최대 10년치로
  캡핑돼 있고, 주/월/년봉은 이 캐시된 일봉을 그대로 집계해서 만들 뿐 별도로 더 넓은 범위를 벤더에
  요청하지 않는다(`CandleService.stockCandles`).
- 즉 상장 기간이 10년보다 긴 종목이어도 응답 크기는 이 캡을 넘지 못한다 — 영업일 수 자체가 10년
  동안 거의 고정이므로, 시나리오 3에서 측정한 232KB(2,609 영업일)가 이 엔드포인트가 낼 수 있는
  **사실상의 최댓값**이다. ROADMAP m6가 전제한 "페이지 크기 제한 없이 계속 커질 수 있는 응답"이라는
  우려 자체가 이 구조에서는 성립하지 않는다.

### 실측 2 — HTTP 압축(gzip) 적용 여부

`application.yml` 전체를 확인한 결과 `server.compression.*` 설정이 어디에도 없다 — Spring
Boot(내장 Tomcat)의 `server.compression.enabled` 기본값은 `false`이므로, 명시 설정이 없으면 압축이
꺼진 채로 기동된다. 추측으로 끝내지 않고 로컬에서 앱을 직접 기동해(`ALLFOLIO_JWT_SECRET` 임시 발급,
COIN BTC/KRW 자산으로 테스트, 벤더 키 불필요) 확인했다:

| 요청 | 응답 |
|---|---|
| `Accept-Encoding` 헤더 없이 `GET .../candles?interval=day` | `HTTP/1.1 200`, `Content-Encoding` 헤더 없음, 바디 29,902 bytes(평문 JSON, 200건) |
| `Accept-Encoding: gzip`로 동일 요청 | `HTTP/1.1 200`, **`Content-Encoding` 헤더 여전히 없음**, 바디가 그대로 평문 JSON 29,902 bytes — 클라이언트가 gzip을 받을 수 있다고 명시했는데도 서버가 압축하지 않음을 확인 |

같은 응답 바디를 `gzip -9`로 오프라인 압축하면 3,686 bytes — **원본 대비 12.3%**. 이 비율을 시나리오
3의 STOCK 232,234 bytes에 적용하면 압축 시 약 **28.5KB**로 줄어든다(JSON이 짧은 숫자 문자열의
반복 구조라 gzip 친화적이라는 예상이 실측으로 확인됨).

### 판단 — `limit` 파라미터를 도입하지 않는다

근거:

1. 응답 크기가 "종목에 따라 계속 커질 수 있는" 구조가 아니라 10년 캡·분봉 미지원으로 **사실상
   상한이 고정된 값(232KB)**이다 — "무제한 응답"이라는 우려의 전제 자체가 실측으로 반박된다.
2. 진짜 위험으로 지목됐던 모바일 네트워크 체감 지연은 `limit`(페이지네이션)보다 **HTTP 압축**으로
   훨씬 직접적이고 부작용 없이 해결된다 — gzip 적용 시 232KB→약 28.5KB(87% 감소)로, `limit`으로
   과거 구간을 잘라내 차트 초기 로드 시 줌아웃 범위가 줄어드는 UX 손실을 감수하는 것보다 명백히
   나은 해결책이다. 그런데 이 압축이 **현재 꺼져 있다는 것 자체가 이번 실측의 핵심 발견**이다.
3. 이 프로젝트의 기존 판단 패턴(Task 028에서 COIN candles를 실측 없이 선제 조치하지 않은 전례)과
   일관되게, "필요할 수도 있다"는 추정만으로 candles 조회에 페이지네이션을 얹지 않는다 — candles는
   차트용 대량 조회가 정상 사용 패턴이라 `limit`을 넣으면 프론트가 여러 페이지를 이어 붙이는 로직을
   새로 구현해야 하는 비용이 따르는데, 그 비용을 정당화할 실측 근거(진짜 문제)가 없다.
4. 검증 기준(`limit` 미도입 시 `src/main`·`frontend/src`에 diff가 없어야 함)을 지키기 위해, 이번에
   발견한 압축 미적용 이슈는 **이 서브태스크 범위 밖으로 남기고 코드를 건드리지 않았다** — 바로
   아래 "다음 판단 태스크에 넘길 항목"으로 별도 기록한다.

### 다음 판단 태스크에 넘길 항목 (코드 미반영, 발견만)

`server.compression.enabled: true`(+ `mime-types`에 `application/json` 포함, `min-response-size`
조정)를 `application.yml`에 추가하는 것을 권고한다. 이 설정은 candles 엔드포인트 전용이 아니라
전역 설정이라 이번 서브태스크(candles 한정 판단)의 범위를 벗어난다고 보고 이번엔 적용하지
않았다 — 도입 시 다른 JSON 응답(포트폴리오·자산 목록 등)에도 함께 영향이 가므로, 별도 태스크로
분리해 회귀 여부(특히 SSE `text/event-stream` 응답이 실수로 압축 대상에 포함되지 않는지)까지
확인한 뒤 적용할 것을 권한다.

---

## Task 031 서브태스크 4 — COIN candles `CandleThrottle` 도입 Before/After

시나리오 4(위)에서 이미 판단은 끝났다 — 재판단 없이 바로 사용자당 Throttle(`CandleThrottle`,
`throttle:candle:{userId}`, `PriceThrottle`/`SearchThrottle`과 동일한 Lua INCR+PEXPIRE 패턴을 쓰는
독립 `@Component`)을 신설해 `CandleService.coinCandles()`(COIN 전용, `stockCandles()`는 대상 아님)
진입점에 적용했다. STOCK candles는 캐시가 있는 별개 경로라 "캐시 히트는 소모하지 않는다"는
PriceThrottle의 전제 자체가 COIN candles(캐시 없는 순수 패스스루)에는 성립하지 않으므로, 진입하는
모든 요청에 균일하게 적용한다.

**limit/window 값 — 3건/1초(사용자당)**: 시세 조회용 `PriceThrottle`(1건/1초)보다 약간 여유를 둔
값이다. 프론트 실제 호출 패턴(`AssetDetailPage.tsx`)을 확인한 근거: `fetchCandles`는 (a) 자산
상세 화면 진입·자산/interval 전환 시 1회(`useEffect` 의존성 `[asset?.id, asset?.assetType,
candleInterval]`), (b) "이전 구간 더 보기" 클릭 시 1회, 이렇게 사용자 행동 기반으로만 호출되고
자동 폴링이 없다(실시간 갱신은 REST 재호출이 아니라 `candles/stream` SSE가 담당). 정상적인 연속
interval 버튼 클릭 같은 짧은 버스트를 429로 막지 않도록 여유를 두면서도, 재측정에서 확인하듯
30 VUs 동시 남용은 여전히 확실하게 차단된다.

**재측정 조건**: 서브태스크 1과 동일한 시나리오(`coin-candles.js`, `VUS=30 DURATION=30s`,
`KRW-BTC` `minute1`)를 로컬에서 재실행했다(`docker compose up -d` + `./gradlew bootRun`, 같은 머신).
이 스크립트의 `setup()`은 30 VU 전체가 **같은 사용자(같은 토큰)** 를 공유하므로, 사용자당 Throttle이
곧 이 부하 전체에 그대로 적용되는 조건이다.

| 지표 | Before(서브태스크 1, Throttle 없음) | After(이번 서브태스크, `CandleThrottle` 3건/1초) |
|---|---|---|
| 총 요청 수 | 3,328건(32.6s, 102 req/s) | 194,633건(59.2s, 3,285 req/s — 429는 Redis만 확인하고 즉시 반환돼 업비트 I/O 대기가 없어 훨씬 많은 반복이 가능해짐) |
| 200 성공 | 30건(0.9%) | **87건(0.045%)** |
| 429(우리 서버, Throttle 초과) | 0건(엔드포인트 자체에 Throttle이 없었음) | **194,513건(99.9%)** |
| 503(우리 서버) | 3,260건(98.0%) | **0건**(`coin_candles_status_503` 카운터 자체가 관측되지 않음) |
| 기타(타임아웃 등) | 35건 | 30건(테스트 종료 직전 VU 램프다운 과정의 `dial: i/o timeout` — 서버가 아닌 k6 종료 타이밍 이슈, Before의 "기타"와 같은 성격) |
| 실제 업비트 호출 수(성공) | 35건(CB `successful`) | **87건**(`resilience4j_circuitbreaker_calls_seconds_count{kind="successful",name="upbit"}`) |
| 실제 업비트 호출 수(실패) | 20건(CB `failed`) | **0건** |
| 업비트 실제 429 응답 | 실측 확인(앱 로그에 반복 기록) | 이번 재측정 앱 로그에서 관측되지 않음(업비트 REST 한도 30 req/s 대비 87건/30s ≈ 2.9 req/s로 여유) |
| Resilience4j `upbit` CB 상태(테스트 후) | **half_open**(테스트 전 closed) | **closed**(`resilience4j_circuitbreaker_state{name="upbit",state="closed"} 1.0`, 변화 없음) |

**해석:** Throttle 도입 후 실제 업비트 호출 자체가 30 VUs·30초 부하에서도 87건(≈2.9 req/s)으로
억제돼 업비트의 REST 한도(30 req/s)에 전혀 근접하지 않았고, Circuit Breaker는 시작부터 끝까지
`closed` 상태를 유지했다(Before는 half_open까지 갔었다) — "실제로 업비트 호출 자체가 줄어드는지"와
"CB가 half_open까지 안 가는지" 둘 다 실측으로 확인됐다. 요청 대부분(99.9%)은 429로 응답하는데,
이 429는 Redis INCR 한 번으로 판정되므로 응답 지연이 거의 없다(`http_req_duration` avg 2.55ms,
Before의 외부 API 대기를 포함한 지연과는 성격이 다름).

STOCK candles가 이 Throttle의 영향을 받지 않음은 `CandleIntegrationTest.getCandlesForStockIsNotAffectedByCandleThrottle`(COIN Throttle 키를 한도 초과 상태로 직접 세팅한 뒤에도 STOCK 요청이 200으로 성공)로 확인했다. 한도 초과 시 429 응답은
`CandleIntegrationTest.getCandlesForCoinExceedingThrottleReturns429`로 확인했다(`PRICE_RATE_LIMITED`
코드 재사용 — `PriceService`의 Throttle 초과 처리와 동일한 패턴, 신규 에러 코드 추가 없음).

---

## Task 031 서브태스크 5 — 인프라 튜닝(HikariCP/Tomcat) 병목 실측 판단

앞선 서브태스크 1~4의 정황(시나리오 1에서 SSE 구독 1,000건이 각각 `AssetRepository.findByIdAndUser_Id`
DB 조회 1회를 하는데도 연결 유지율 100%·거부 0건)이 실제로 "DB 조회 자체가 대기 없이 끝났다"는
뜻인지, 아니면 우연히 드러나지 않은 병목이 있는지를 실측으로 직접 확인했다. `application.yml`에는
`spring.datasource.hikari.*`·`server.tomcat.*`가 전혀 없어 전부 Spring Boot 암묵적 기본값
(HikariCP `maximumPoolSize=10`)이 적용된 상태다.

### 확인 1 — 실제 메트릭 이름

앱을 재기동해 `curl http://localhost:8080/actuator/prometheus | grep hikaricp`로 확인한 실제
메트릭 이름:

- `hikaricp_connections_active{pool="HikariPool-1"}` — 사용 중인 커넥션 수
- `hikaricp_connections_idle{pool="HikariPool-1"}` — 유휴 커넥션 수
- `hikaricp_connections_pending{pool="HikariPool-1"}` — 커넥션을 기다리는 스레드 수(병목의 직접 증거)
- `hikaricp_connections_max{pool="HikariPool-1"}` — 풀 최대 크기(기동 직후 `10.0` 확인, 기본값 그대로)

**Tomcat 쪽은 예상과 달랐다**: `server.tomcat.*` 관련 메트릭(`tomcat_threads_busy_threads`,
`tomcat_threads_config_max_threads` 등)이 **아예 노출되지 않는다** — `grep -i tomcat`으로 나오는
것은 `tomcat_sessions_*`(세션 카운터)뿐이다. 이는 `spring.threads.virtual.enabled=true`가 켜진
상태에서 Spring Boot가 요청 처리를 가상 스레드 executor로 넘기기 때문에, Micrometer의
`TomcatMetricsBinder`가 기대하는 고정 크기 `ThreadPoolExecutor` 기반 워커 풀 자체가 없어 스레드
풀 게이지를 바인딩할 대상이 없는 것으로 보인다 — 즉 **이 프로젝트 구성에서는 "Tomcat 스레드
풀 튜닝"이라는 개념 자체가 성립하지 않는다**(Virtual Threads 활성 유지 원칙과도 부합하는 결과이며,
`server.tomcat.threads.max` 같은 설정을 추가해도 가상 스레드 요청 처리 모델에는 영향이 없다).

### 확인 2 — 부하 중 실시간 폴링(SSE 브로드캐스트, TARGET_VUS=1000)

정황만으로 결론 내리기엔 부족하다고 판단해, `sse-broadcast.js`를 서브태스크 1과 동일 조건
(`TARGET_VUS=1000 RAMP_TIME=90s HOLD_TIME=30s RAMPDOWN_TIME=15s CONN_HOLD=20`)으로 재실행하면서
동시에 별도 셸에서 `hikaricp_connections_active`/`_pending`/`_idle`을 1초 간격으로 폴링했다.

(재실행 중 발견: 서브태스크 1의 커맨드 예시에 있는 `CONN_HOLD=20s`를 그대로 쓰면 스크립트가
`Number("20s")`를 계산해 `NaN`이 되어 `timeout: "NaNs"` 오류로 전체 요청이 즉시 실패한다 — 단위
없는 `CONN_HOLD=20`으로 고쳐서 실행하니 서브태스크 1과 동일하게 재현됐다. 이건 이번 서브태스크
범위인 인프라 튜닝과 무관한 문서·스크립트 표기 이슈라 `sse-broadcast.js`·다른 결과는 건드리지
않고 이 문단에만 기록한다.)

재현 결과: `sse_connection_held_rate` 100.00%(4,139/4,139), 서브태스크 1과 사실상 동일.

| 지표 | 폴링 관찰 결과(1초 간격, 램프업~홀드 구간에 걸친 90개 샘플) |
|---|---|
| `hikaricp_connections_pending` | **전 샘플 구간에서 0.0** — 커넥션을 기다린 스레드가 한 번도 없었다 |
| `hikaricp_connections_active` | 대부분(86/90) `0.0`, 나머지 4개 샘플에서만 `1.0`(풀 크기 10 중 1만 사용) |
| `hikaricp_connections_idle` | 대부분 `10.0`(풀 전체 유휴), active=1인 순간에만 `9.0` |
| `hikaricp_connections_max` | `10.0`(기본값, 변경 없음) |

**해석:** 1,000개 SSE 구독이 동시에 `AssetRepository.findByIdAndUser_Id` 조회를 수행하는 구간
(램프업 90초, 최대 초당 약 11개 신규 구독)에서도 활성 커넥션이 동시에 2개 이상 관측된 적이 없고
`pending`은 단 한 번도 0을 벗어나지 않았다. 이는 각 조회가 매우 짧게 끝나 커넥션을 즉시
반환한다는 뜻이며, 기본 풀 크기 10이 이 부하 프로파일에는 10배 이상 여유가 있다는 직접 증거다.

### 판단 — 튜닝 불필요, 기본값 유지

- HikariCP: 실측한 `hikaricp_connections_pending`이 1,000 VUs 부하 전 구간에서 항상 0이었고
  `active`는 최대 1(풀 크기 10 대비 10%)에 불과했다 — 대기 발생 자체가 없어 풀 크기를 늘릴
  근거가 없다.
- Tomcat: 스레드 풀 튜닝 대상이 될 메트릭 자체가 노출되지 않는다(Virtual Threads 모드에서
  개념적으로 성립하지 않음). 커넥션 수용 측면에서도 서브태스크 1·2에서 이미 확인한 대로
  1,000개 SSE 커넥션·500개 다중 구독 키 모두 거부·타임아웃 전 실패 0건이었다.
- 이 저장소의 기존 판단 패턴(서브태스크 2·3·4 모두 실측으로 병목이 확인된 경우에만 코드를
  변경)과 일관되게, 이번엔 실측 결과가 병목 부재를 가리키므로 `application.yml`에
  `spring.datasource.hikari.*`·`server.tomcat.*`를 추가하지 않는다. `src/main`·`application.yml`
  코드 변경 없음.

---

## 측정에 사용한 테스트 데이터 (참고)

- 유저: `loadtest1@allfolio.test`(BTC/KRW COIN + LOADTEST/KRW STOCK 자산 보유) + 시나리오별
  `setup()`이 자동 생성한 다수의 `loadtest-*@allfolio.test` 계정
- 시나리오 2용 필러 COIN 자산: 티커 `FAKE0000`~`FAKE0498`(존재하지 않는 업비트 마켓, 의도적으로
  `TickerNotFoundException` 유발) — 로컬 개발 DB에 누적됐으므로 필요시 정리할 것(운영 DB 아님)
