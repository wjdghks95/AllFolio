// Task 007~011이 실제 API 없이 화면을 완성할 때 import하는 더미 응답.
// types.ts와 분리해두는 이유: Task 018에서 실데이터로 교체할 때 이 파일의 import만
// 제거하면 되고, types.ts는 계약의 단일 출처로 그대로 남는다.
import type {
  Asset,
  AssetListResponse,
  ErrorResponse,
  PortfolioResponse,
  SimulateAvgPriceResponse,
} from './types';

// GET /v1/assets 더미 응답.
// STOCK(KRW) / COIN / CASH / STOCK(USD)를 섞고, 결정 #3(중복 등록 허용)에 따라
// 같은 티커(005930 삼성전자)를 서로 다른 id로 2건 포함한다.
export const assetListFixture: AssetListResponse = {
  items: [
    {
      id: '0198f2a1-0001-7c3a-8f21-000000000001',
      ticker: '005930',
      name: '삼성전자',
      assetType: 'STOCK',
      currency: 'KRW',
      quantity: '10',
      avgPrice: '60000',
      version: 0,
      updatedAt: '2026-08-17T09:00:00Z',
    },
    {
      id: '0198f2a1-0002-7c3a-8f21-000000000002',
      ticker: '005930',
      name: '삼성전자',
      assetType: 'STOCK',
      currency: 'KRW',
      quantity: '3',
      avgPrice: '58500',
      version: 0,
      updatedAt: '2026-08-17T09:05:00Z',
    },
    {
      id: '0198f2a1-0003-7c3a-8f21-000000000003',
      ticker: 'BTC',
      name: '비트코인',
      assetType: 'COIN',
      currency: 'KRW',
      quantity: '0.05123456',
      avgPrice: '82000000',
      version: 0,
      updatedAt: '2026-08-17T09:10:00Z',
    },
    {
      id: '0198f2a1-0004-7c3a-8f21-000000000004',
      ticker: 'KRW',
      name: '원화 예수금',
      assetType: 'CASH',
      currency: 'KRW',
      quantity: '1500000',
      // CASH는 서버가 강제 삽입하는 값 (ROADMAP 결정 #1)
      avgPrice: '1',
      version: 0,
      updatedAt: '2026-08-17T09:15:00Z',
    },
    {
      id: '0198f2a1-0005-7c3a-8f21-000000000005',
      ticker: 'AAPL',
      name: 'Apple Inc.',
      assetType: 'STOCK',
      currency: 'USD',
      quantity: '12',
      avgPrice: '182.5000',
      version: 0,
      updatedAt: '2026-08-17T09:20:00Z',
    },
  ],
  nextCursor: null,
};

// GET /v1/assets/{id} 더미 응답 (단건 상세).
export const assetDetailFixture: Asset = assetListFixture.items[0];

// GET /v1/portfolio 더미 응답.
// evaluationKrw/unrealizedPnl/weight는 외부 시세 연동 전(Phase 2)이라 전부 null —
// 화면이 이 값을 "—"로 표시하도록 강제하는 용도.
export const portfolioFixture: PortfolioResponse = {
  items: [
    {
      assetId: '0198f2a1-0001-7c3a-8f21-000000000001',
      ticker: '005930',
      name: '삼성전자',
      assetType: 'STOCK',
      currency: 'KRW',
      quantity: '10',
      avgPrice: '60000',
      cost: '600000',
      evaluationKrw: null,
      unrealizedPnl: null,
      weight: null,
    },
    {
      assetId: '0198f2a1-0002-7c3a-8f21-000000000002',
      ticker: '005930',
      name: '삼성전자',
      assetType: 'STOCK',
      currency: 'KRW',
      quantity: '3',
      avgPrice: '58500',
      cost: '175500',
      evaluationKrw: null,
      unrealizedPnl: null,
      weight: null,
    },
    {
      assetId: '0198f2a1-0003-7c3a-8f21-000000000003',
      ticker: 'BTC',
      name: '비트코인',
      assetType: 'COIN',
      currency: 'KRW',
      quantity: '0.05123456',
      avgPrice: '82000000',
      // 0.05123456 × 82,000,000 = 4,201,233.92 (코인 스케일 8, ROADMAP 「금융 정밀도 규칙」)
      cost: '4201233.92000000',
      evaluationKrw: null,
      unrealizedPnl: null,
      weight: null,
    },
    {
      assetId: '0198f2a1-0004-7c3a-8f21-000000000004',
      ticker: 'KRW',
      name: '원화 예수금',
      assetType: 'CASH',
      currency: 'KRW',
      quantity: '1500000',
      avgPrice: '1',
      cost: '1500000',
      evaluationKrw: null,
      unrealizedPnl: null,
      weight: null,
    },
    {
      assetId: '0198f2a1-0005-7c3a-8f21-000000000005',
      ticker: 'AAPL',
      name: 'Apple Inc.',
      assetType: 'STOCK',
      currency: 'USD',
      quantity: '12',
      avgPrice: '182.5000',
      cost: '2190.0000',
      evaluationKrw: null,
      unrealizedPnl: null,
      weight: null,
    },
  ],
  // KRW: 600000(삼성전자 1) + 175500(삼성전자 2) + 4201233.92(BTC) + 1500000(원화 예수금) = 6476733.92
  totalCostByCurrency: {
    KRW: '6476733.92',
    USD: '2190.0000',
  },
  totalEvaluationKrw: null,
  totalUnrealizedPnl: null,
};

// POST /v1/simulate/avg-price 더미 응답 — ROADMAP 골든 케이스.
// 기존 평단 60,000원 × 10주 + 추가 55,000원 × 5주
// → (600,000 + 275,000) / 15 = 58,333.33... → HALF_UP, scale 0 → 58,333원
export const simulateAvgPriceFixture: SimulateAvgPriceResponse = {
  currentAvgPrice: '60000',
  expectedAvgPrice: '58333',
  expectedQuantity: '15.00000000',
  currentWeight: null,
  expectedWeight: null,
  calculatedAt: '2026-08-17T09:30:00Z',
};

// 에러 응답 샘플 3종.
export const validationErrorFixture: ErrorResponse = {
  code: 'VALIDATION_ERROR',
  message: '요청 값이 올바르지 않습니다.',
  timestamp: '2026-08-17T09:00:00Z',
};

export const assetNotFoundErrorFixture: ErrorResponse = {
  code: 'ASSET_NOT_FOUND',
  message: '해당 자산을 찾을 수 없습니다.',
  timestamp: '2026-08-17T09:00:00Z',
};

export const holdingConflictErrorFixture: ErrorResponse = {
  code: 'HOLDING_CONFLICT',
  message: '다른 곳에서 이미 수정되었습니다. 새로고침 후 다시 시도하세요.',
  timestamp: '2026-08-17T09:00:00Z',
};
