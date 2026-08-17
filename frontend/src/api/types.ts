// 백엔드 응답 계약과 1:1 대응하는 타입 정의 (ROADMAP.md 「API 규격」이 원본).
// 이 파일이 계약의 단일 출처(single source of truth)다 — 필드를 추가/변경할 때는
// 반드시 docs/ROADMAP.md 「API 규격」 절도 함께 갱신할 것.

// tsconfig.app.json의 erasableSyntaxOnly로 `enum` 키워드를 쓸 수 없어
// as const 배열 + 인덱스 유니온 패턴을 사용한다. 배열도 함께 export해
// 자산 등록 화면(Task 010)의 select 옵션을 이 배열로 렌더링할 수 있게 한다.
export const ASSET_TYPES = ['STOCK', 'COIN', 'CASH'] as const;
export type AssetType = (typeof ASSET_TYPES)[number];

// 금액·수량 필드는 NUMERIC(28,8)이라 JS number(IEEE 754 배정도)가 정밀도를
// 담지 못한다. 백엔드가 항상 JSON 문자열로 내려주므로 이 타입도 string으로
// 고정한다. parseFloat/Number() 변환 금지 — decimal.js 등 십진 라이브러리로 처리.
export type Money = string;

export interface Asset {
  id: string;
  ticker: string;
  name: string;
  assetType: AssetType;
  currency: string;
  quantity: Money;
  avgPrice: Money;
  version: number;
  // ISO-8601 문자열 (예: "2026-08-17T09:00:00Z")
  updatedAt: string;
}

export interface AssetListResponse {
  items: Asset[];
  // 불투명 문자열 — 클라이언트는 값을 해석하지 않고 다음 요청의 cursor로만 전달한다.
  nextCursor: string | null;
}

export interface CreateAssetRequest {
  ticker: string;
  name: string;
  assetType: AssetType;
  currency: string;
  quantity: Money;
  // CASH면 null (서버가 avgPrice=1을 강제 삽입, ROADMAP 결정 #1)
  avgPrice: Money | null;
}

export interface UpdateHoldingRequest {
  quantity: Money;
  // 대상 자산이 CASH면 서버가 값을 무시하고 1을 유지한다. 그 외 자산 타입에서 null을 보내면
  // POST와 동일하게 400 VALIDATION_ERROR가 난다 (ROADMAP 「Bean Validation 규칙」).
  avgPrice: Money | null;
  // 낙관적 잠금 비교용 — 상세 조회 응답의 version을 그대로 되돌려 보낸다.
  version: number;
}

// PUT /v1/assets/{id}/holdings 응답도 Asset과 동일 형태 (version이 갱신된 값).

export interface PortfolioItem {
  assetId: string;
  ticker: string;
  name: string;
  assetType: AssetType;
  currency: string;
  quantity: Money;
  avgPrice: Money;
  // 취득원가 (avgPrice × quantity)
  cost: Money;
  // evaluationKrw/unrealizedPnl/weight는 외부 시세 연동 전(Phase 2)에는 항상 null.
  // Task 023(Phase 3)에서 채워진다.
  evaluationKrw: Money | null;
  unrealizedPnl: Money | null;
  weight: Money | null;
}

export interface PortfolioResponse {
  items: PortfolioItem[];
  // 통화별 취득원가 합계. 단일 KRW 합계로 두면 환율 없이 USD 자산을 섞을 수 없어
  // 통화별 Map으로 정직하게 담는다. 예: { KRW: "2100000", USD: "1200.0000" }
  totalCostByCurrency: Record<string, Money>;
  totalEvaluationKrw: Money | null;
  totalUnrealizedPnl: Money | null;
}

export interface SimulateAvgPriceRequest {
  assetId: string;
  additionalPrice: Money;
  additionalQuantity: Money;
}

export interface SimulateAvgPriceResponse {
  currentAvgPrice: Money;
  expectedAvgPrice: Money;
  // 포트폴리오 내 비중(%). 분모인 전체 평가금액이 외부 시세 필요라 Phase 2에서는 null.
  currentWeight: Money | null;
  expectedWeight: Money | null;
  // ISO-8601 문자열
  calculatedAt: string;
}

export const ERROR_CODES = [
  'EMAIL_ALREADY_EXISTS',
  'INVALID_CREDENTIALS',
  'UNAUTHORIZED',
  'VALIDATION_ERROR',
  'NOT_FOUND',
  'METHOD_NOT_ALLOWED',
  'UNSUPPORTED_MEDIA_TYPE',
  'NOT_ACCEPTABLE',
  'CLIENT_ERROR',
  'INTERNAL_ERROR',
  'ASSET_NOT_FOUND',
  'HOLDING_CONFLICT',
  'CONFLICT',
] as const;
export type ErrorCode = (typeof ERROR_CODES)[number];

export interface ErrorResponse {
  code: ErrorCode;
  message: string;
  // ISO-8601 문자열
  timestamp: string;
}
