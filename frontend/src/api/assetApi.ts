// 자산·포트폴리오·시뮬레이터 API 클라이언트. authApi.ts와 동일하게 에러는 code만 담아
// 던지고 문구는 만들지 않는다 — 문구 조회(messageForErrorCode)는 화면의 책임이다.
// 모든 요청에 로그인 토큰을 Authorization 헤더로 실어 보낸다(authApi.ts의 signup/login은
// 토큰이 없는 상태에서 호출되므로 이 헤더가 없다 — 그것이 이 파일과의 유일한 차이).
import { ApiError } from './authApi';
import { getToken } from '../auth/tokenStorage';
import type {
  Asset,
  CreateAssetRequest,
  UpdateHoldingRequest,
  PortfolioResponse,
  SimulateAvgPriceRequest,
  SimulateAvgPriceResponse,
  ErrorResponse,
} from './types';

async function authorizedRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;

  let res: Response;
  try {
    res = await fetch(path, { ...options, headers });
  } catch {
    throw new ApiError('NETWORK_ERROR');
  }
  if (!res.ok) {
    const err: ErrorResponse = await res.json();
    throw new ApiError(err.code, err.message);
  }
  // DELETE 성공(204)은 응답 본문이 없다.
  if (res.status === 204) {
    return undefined as T;
  }
  return res.json() as Promise<T>;
}

export function getAsset(id: string): Promise<Asset> {
  return authorizedRequest<Asset>(`/v1/assets/${id}`);
}

export function createAsset(req: CreateAssetRequest): Promise<Asset> {
  return authorizedRequest<Asset>('/v1/assets', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export function updateHolding(id: string, req: UpdateHoldingRequest): Promise<Asset> {
  return authorizedRequest<Asset>(`/v1/assets/${id}/holdings`, {
    method: 'PUT',
    body: JSON.stringify(req),
  });
}

export function deleteAsset(id: string): Promise<void> {
  return authorizedRequest<void>(`/v1/assets/${id}`, { method: 'DELETE' });
}

export function getPortfolio(): Promise<PortfolioResponse> {
  return authorizedRequest<PortfolioResponse>('/v1/portfolio');
}

export function simulateAvgPrice(req: SimulateAvgPriceRequest): Promise<SimulateAvgPriceResponse> {
  return authorizedRequest<SimulateAvgPriceResponse>('/v1/simulate/avg-price', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}
