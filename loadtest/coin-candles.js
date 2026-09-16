// Task 031 서브태스크 1 — 시나리오 4: GET /v1/assets/{id}/candles (COIN, 캐시 없는 패스스루)
//
// COIN 캔들 경로는 캐시·Throttle이 전혀 없다 — 요청 1건 = 업비트 호출 1건(CandleService Javadoc).
// GET /v1/assets/{id}/price(1건/초 Throttle 적용)와 달리 이 경로는 동시 다발 호출을 그대로
// 업비트로 흘려보낸다. 이 스크립트는 동시 다발 호출로 업비트 실호출 빈도와 429/5xx/CB open 여부를
// 관찰한다(우리 서버가 반환하는 상태 코드 기준 — 업비트 자체 429는 UpbitPriceClient가 어떻게
// 처리하는지에 따라 우리 응답이 503/429/200 중 무엇으로 보이는지가 핵심 관찰 대상).
//
// 실행:
//   docker run --rm -i --add-host=host.docker.internal:host-gateway \
//     -e BASE_URL=http://host.docker.internal:8080 \
//     -v $(pwd)/loadtest:/scripts grafana/k6 run /scripts/coin-candles.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

const status200 = new Counter('coin_candles_status_200');
const status429 = new Counter('coin_candles_status_429');
const status503 = new Counter('coin_candles_status_503');
const statusOther = new Counter('coin_candles_status_other');
const responseBytes = new Trend('coin_candles_response_bytes');

export const options = {
  scenarios: {
    coin_burst: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 30),
      duration: __ENV.DURATION || '20s',
    },
  },
};

export function setup() {
  const email = `loadtest-coin-${Date.now()}@allfolio.test`;
  const password = 'loadtest123';
  http.post(`${BASE_URL}/v1/auth/signup`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' } });
  const loginRes = http.post(`${BASE_URL}/v1/auth/login`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' } });
  const token = loginRes.json('accessToken');

  let assetId = __ENV.ASSET_ID;
  if (!assetId) {
    const assetRes = http.post(`${BASE_URL}/v1/assets`, JSON.stringify({
      ticker: __ENV.TICKER || 'BTC', name: 'Bitcoin', assetType: 'COIN', currency: 'KRW',
      quantity: '0.1', avgPrice: '80000000',
    }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } });
    assetId = assetRes.json('id');
  }
  return { token, assetId };
}

export default function (data) {
  const res = http.get(`${BASE_URL}/v1/assets/${data.assetId}/candles?interval=minute1`, {
    headers: { Authorization: `Bearer ${data.token}` },
  });
  responseBytes.add(res.body ? res.body.length : 0);
  if (res.status === 200) status200.add(1);
  else if (res.status === 429) status429.add(1);
  else if (res.status === 503) status503.add(1);
  else statusOther.add(1);
  check(res, {
    'status is 200/429/503': (r) => [200, 429, 503].includes(r.status),
  });
}
