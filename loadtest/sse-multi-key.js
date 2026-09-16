// Task 031 서브태스크 1 — 시나리오 2: SSE 다중 구독 키(CandlePushScheduler 순회 성능)
//
// 유저 1명이 서로 다른 종목(구독 키) N개를 보유하고 각각 별도 SSE 커넥션을 연다. 이러면
// CandlePushScheduler.pollAndPush()가 매 10초 틱(allfolio.price-cache.coin-fresh-ttl)마다
// registry.activeKeys()로 순회할 구독 키가 N개로 늘어난다 — 이 스크립트의 목적은 "N이 늘어날수록
// 폴링 틱이 10초 안에 다 끝나는지"를 실측하는 것(다음 태스크의 병렬화 여부 판단 근거).
//
// 구성:
//   - marker 시나리오(VU 1개, 고정): 실제 업비트가 다루는 BTC/KRW를 구독해 candle 이벤트의
//     id(전송 시각 epoch ms)를 모아 연속 이벤트 간 간격(gap)을 계산한다. 순회가 10초 안에 끝나면
//     이 gap은 10초 근처에 머물고, 못 끝나면 N에 비례해 gap이 늘어난다.
//   - fillers 시나리오(최대 FILLER_VUS개, ramping-vus): 업비트에 존재하지 않는 가짜 티커
//     (FAKE0001…)로 만든 COIN 자산을 구독해 "구독 키 개수"만 채운다. 실제로 값이 바뀌지 않는
//     마켓이라 이 키들 자체는 candle 이벤트를 못 받지만(빈 배열 응답 → pushIfChanged가 조용히
//     스킵), pollAndPush()의 순차 for-loop가 이 키들도 매 틱 업비트에 실제로 물어보므로 순회
//     시간에는 그대로 반영된다.
//
// 표준 k6가 SSE를 지원하지 않는 제약과 http.get() 타임아웃 근사 방식은 sse-broadcast.js/README 참고.
//
// 실행 예 (N=200으로 축소 예시, 1000까지 올리려면 FILLER_VUS=999):
//   docker run --rm -i --add-host=host.docker.internal:host-gateway \
//     -e BASE_URL=http://host.docker.internal:8080 \
//     -e FILLER_VUS=199 -e RAMP_TIME=60s -e HOLD_TIME=40s -e RAMPDOWN_TIME=10s \
//     -e MARKER_HOLD=105s \
//     -v $(pwd)/loadtest:/scripts grafana/k6 run /scripts/sse-multi-key.js

import http from 'k6/http';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const FILLER_VUS = Number(__ENV.FILLER_VUS || 199); // marker 1개 + filler N = 총 구독 키 수
const RAMP_TIME = __ENV.RAMP_TIME || '60s';
const HOLD_TIME = __ENV.HOLD_TIME || '40s';
const RAMPDOWN_TIME = __ENV.RAMPDOWN_TIME || '10s';
// marker는 테스트 전체를 커버하도록 RAMP+HOLD+RAMPDOWN 합보다 살짝 길게 잡는다.
const MARKER_HOLD_SECONDS = Number(__ENV.MARKER_HOLD || 105);
const FILLER_HOLD_SECONDS = Number(__ENV.FILLER_HOLD || 45);

const candlePushGapMs = new Trend('sse_marker_candle_push_gap_ms');
const candlePushCount = new Counter('sse_marker_candle_push_count');
const heartbeatCount = new Counter('sse_marker_heartbeat_count');
const fillerConnDurationMs = new Trend('sse_filler_connection_duration_ms');

export const options = {
  scenarios: {
    marker: {
      executor: 'constant-vus',
      vus: 1,
      duration: MARKER_HOLD_SECONDS + 's',
      exec: 'markerFn',
    },
    fillers: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_TIME, target: FILLER_VUS },
        { duration: HOLD_TIME, target: FILLER_VUS },
        { duration: RAMPDOWN_TIME, target: 0 },
      ],
      gracefulRampDown: '10s',
      exec: 'fillerFn',
    },
  },
};

export function setup() {
  const email = `loadtest-sse-multikey-${Date.now()}@allfolio.test`;
  const password = 'loadtest123';
  http.post(`${BASE_URL}/v1/auth/signup`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' } });
  const loginRes = http.post(`${BASE_URL}/v1/auth/login`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' } });
  const token = loginRes.json('accessToken');
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };

  const markerRes = http.post(`${BASE_URL}/v1/assets`, JSON.stringify({
    ticker: 'BTC', name: 'Bitcoin', assetType: 'COIN', currency: 'KRW',
    quantity: '0.1', avgPrice: '80000000',
  }), { headers });
  const markerAssetId = markerRes.json('id');

  const fillerAssetIds = [];
  for (let i = 0; i < FILLER_VUS; i++) {
    const ticker = `FAKE${String(i).padStart(4, '0')}`;
    const res = http.post(`${BASE_URL}/v1/assets`, JSON.stringify({
      ticker, name: `Fake ${ticker}`, assetType: 'COIN', currency: 'KRW',
      quantity: '1', avgPrice: '1000',
    }), { headers });
    if (res.status === 201) {
      fillerAssetIds.push(res.json('id'));
    }
  }

  return { token, markerAssetId, fillerAssetIds };
}

export function markerFn(data) {
  const url = `${BASE_URL}/v1/assets/${data.markerAssetId}/candles/stream?interval=day&token=${data.token}`;
  const res = http.get(url, { timeout: `${MARKER_HOLD_SECONDS}s` });
  const body = res.body || '';

  heartbeatCount.add((body.match(/:heartbeat/g) || []).length);

  // "id:<epoch ms>" 형태의 candle 이벤트 전송 시각을 순서대로 뽑아 연속 이벤트 간 간격을 잰다.
  // 줄바꿈 스타일(\n vs \r\n)에 기대지 않도록 앵커 없이 매칭한다.
  const ids = [];
  const re = /id:(\d+)/g;
  let m;
  while ((m = re.exec(body)) !== null) {
    ids.push(Number(m[1]));
  }
  candlePushCount.add(ids.length);
  for (let i = 1; i < ids.length; i++) {
    candlePushGapMs.add(ids[i] - ids[i - 1]);
  }
}

export function fillerFn(data) {
  if (data.fillerAssetIds.length === 0) {
    return;
  }
  const assetId = data.fillerAssetIds[(__VU - 1) % data.fillerAssetIds.length];
  const url = `${BASE_URL}/v1/assets/${assetId}/candles/stream?interval=day&token=${data.token}`;
  const res = http.get(url, { timeout: `${FILLER_HOLD_SECONDS}s` });
  fillerConnDurationMs.add(res.timings.duration);
}
