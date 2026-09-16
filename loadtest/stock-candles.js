// Task 031 서브태스크 1 — 시나리오 3: GET /v1/assets/{id}/candles (STOCK, 캐시 히트)
//
// STOCK 캔들은 페이지 크기 제한 없이 캐시 전량(최대 10년치 일봉, 실측 최대 ~2,450건)을 한 응답으로
// 반환한다(ROADMAP Task 031 배경). 이 스크립트는 이미 캐시가 채워진 상태에서 반복 호출해 응답
// 크기·지연을 측정한다 — 캐시 미스(벤더 실호출) 경로는 측정 대상이 아니다.
//
// 사전 준비: setup()이 회원가입 + STOCK 자산 등록까지 해주지만, "캐시 히트" 상태를 보장하려면
// Redis에 해당 자산의 캔들 캐시가 이미 채워져 있어야 한다(ALLFOLIO_STOCK_SERVICE_KEY가 없으면
// 첫 호출이 503 EXTERNAL_API_DOWN으로 실패해 캐시가 채워지지 않는다). 이 세션에서는
// candle:STOCK:LOADTEST 키를 별도 시딩(임시 JUnit 테스트, 실행 후 삭제)으로 미리 채워뒀다 —
// loadtest/README.md 참고. ASSET_ID/TICKER를 환경변수로 넘기면 이미 존재하는 자산을 재사용할 수 있다.
//
// 실행:
//   docker run --rm -i --add-host=host.docker.internal:host-gateway \
//     -e BASE_URL=http://host.docker.internal:8080 \
//     -e ASSET_ID=<이미 캐시가 채워진 STOCK 자산 UUID> \
//     -v $(pwd)/loadtest:/scripts grafana/k6 run /scripts/stock-candles.js

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const TICKER = __ENV.TICKER || 'LOADTEST';

const responseBytes = new Trend('stock_candles_response_bytes');
const barCount = new Trend('stock_candles_bar_count');

export const options = {
  scenarios: {
    stock_cache_hit: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '20s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  // ASSET_ID로 이미 캐시가 채워진 자산을 재사용하려면 그 자산을 소유한 계정의 EMAIL/PASSWORD도
  // 함께 넘겨야 한다 — 타 유저 소유 자산은 404로 막히기 때문이다(ROADMAP 소유권 컨벤션).
  const email = __ENV.EMAIL || `loadtest-stock-${Date.now()}@allfolio.test`;
  const password = __ENV.PASSWORD || 'loadtest123';
  if (!__ENV.EMAIL) {
    http.post(`${BASE_URL}/v1/auth/signup`, JSON.stringify({ email, password }),
        { headers: { 'Content-Type': 'application/json' } });
  }
  const loginRes = http.post(`${BASE_URL}/v1/auth/login`, JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' } });
  const token = loginRes.json('accessToken');

  let assetId = __ENV.ASSET_ID;
  if (!assetId) {
    const assetRes = http.post(`${BASE_URL}/v1/assets`, JSON.stringify({
      ticker: TICKER, name: 'Load Test Stock', assetType: 'STOCK', currency: 'KRW',
      quantity: '10', avgPrice: '70000',
    }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } });
    assetId = assetRes.json('id');
  }
  return { token, assetId };
}

export default function (data) {
  const res = http.get(`${BASE_URL}/v1/assets/${data.assetId}/candles?interval=day`, {
    headers: { Authorization: `Bearer ${data.token}` },
  });
  responseBytes.add(res.body ? res.body.length : 0);
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
  if (res.status === 200) {
    const body = res.json();
    barCount.add(body.bars ? body.bars.length : 0);
  }
}
