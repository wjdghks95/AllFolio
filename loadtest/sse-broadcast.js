// Task 031 서브태스크 1 — 시나리오 1: SSE 브로드캐스트(단일 구독 키)
//
// 같은 CandleSubscriptionKey(같은 티커·통화·interval, 기본 BTC/KRW/day)에 emitter를 최대
// TARGET_VUS(기본 1000)개까지 ramping-vus로 늘리며 연결 유지율·heartbeat/candle 이벤트 수신·
// push 지연을 측정한다.
//
// == 표준 k6는 EventSource/SSE 프로토콜을 지원하지 않는다(loadtest/README.md 기술 조사 참고) ==
// 그래서 http.get()으로 스트림을 열고 고정 timeout까지 연결을 붙들어 "연결 유지"를 근사 측정한다.
// SSE 스트림은 서버가 먼저 끊지 않는 한 계속 열려 있으므로, http.get()은 요청한 timeout 전체를
// 기다렸다가 반드시 "request timeout"(k6 error_code 1050)으로 끝난다 — 이건 실패가 아니라
// "그 timeout 동안 연결이 끊기지 않고 살아있었다"는 뜻이다. 반대로 연결이 즉시 거부되거나
// 인증 실패(401)면 duration이 timeout보다 훨씬 짧게 끝난다. 그래서 성공 판정은
// `duration >= timeoutMs * 0.9`로 한다.
//
// k6 http.get()은 타임아웃으로 끊겨도 그 순간까지 수신한 바디를 그대로 돌려준다(실측 확인,
// README 참고) — 그래서 ":heartbeat"/"event:candle" 등장 횟수를 세어 실제 이벤트 수신 여부도
// 함께 확인할 수 있다.
//
// 실행 예:
//   docker run --rm -i --add-host=host.docker.internal:host-gateway \
//     -e BASE_URL=http://host.docker.internal:8080 \
//     -e TARGET_VUS=1000 -e RAMP_TIME=90s -e HOLD_TIME=30s -e RAMPDOWN_TIME=15s -e CONN_HOLD=20 \
//     -v $(pwd)/loadtest:/scripts grafana/k6 run /scripts/sse-broadcast.js

import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const TICKER = __ENV.TICKER || 'BTC';
const TARGET_VUS = Number(__ENV.TARGET_VUS || 1000);
const RAMP_TIME = __ENV.RAMP_TIME || '90s';
const HOLD_TIME = __ENV.HOLD_TIME || '30s';
const RAMPDOWN_TIME = __ENV.RAMPDOWN_TIME || '15s';
// 개별 http.get() 1건이 붙들고 있을 시간. RAMP_TIME+HOLD_TIME보다 짧게 잡아야 한 VU가 여러 번
// 반복 접속하며 여러 샘플을 남긴다(한 번만 접속하고 테스트 끝까지 물고 있으면 표본이 1개뿐).
const CONN_HOLD_SECONDS = Number(__ENV.CONN_HOLD || 20);

const connectionsHeld = new Rate('sse_connection_held_rate');
const heartbeatEvents = new Counter('sse_heartbeat_events_received');
const candleEvents = new Counter('sse_candle_events_received');
const connDurationMs = new Trend('sse_connection_duration_ms');

export const options = {
  scenarios: {
    broadcast: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_TIME, target: TARGET_VUS },
        { duration: HOLD_TIME, target: TARGET_VUS },
        { duration: RAMPDOWN_TIME, target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
};

export function setup() {
  const email = `loadtest-sse-broadcast-${Date.now()}@allfolio.test`;
  const password = 'loadtest123';
  http.post(`${BASE_URL}/v1/auth/signup`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' } });
  const loginRes = http.post(`${BASE_URL}/v1/auth/login`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' } });
  const token = loginRes.json('accessToken');

  let assetId = __ENV.ASSET_ID;
  if (!assetId) {
    const assetRes = http.post(`${BASE_URL}/v1/assets`, JSON.stringify({
      ticker: TICKER, name: 'Bitcoin', assetType: 'COIN', currency: 'KRW',
      quantity: '0.1', avgPrice: '80000000',
    }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } });
    assetId = assetRes.json('id');
  }
  return { token, assetId };
}

export default function (data) {
  const url = `${BASE_URL}/v1/assets/${data.assetId}/candles/stream?interval=day&token=${data.token}`;
  const timeoutMs = CONN_HOLD_SECONDS * 1000;
  const res = http.get(url, { timeout: `${CONN_HOLD_SECONDS}s` });

  connDurationMs.add(res.timings.duration);
  const held = res.timings.duration >= timeoutMs * 0.9;
  connectionsHeld.add(held);

  const body = res.body || '';
  const heartbeats = (body.match(/:heartbeat/g) || []).length;
  const candles = (body.match(/event:candle/g) || []).length;
  heartbeatEvents.add(heartbeats);
  candleEvents.add(candles);
}
