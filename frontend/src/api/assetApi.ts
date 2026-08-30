// 자산·포트폴리오·시뮬레이터 API 클라이언트. authApi.ts와 동일하게 에러는 code만 담아
// 던지고 문구는 만들지 않는다 — 문구 조회(messageForErrorCode)는 화면의 책임이다.
// 모든 요청에 로그인 토큰을 Authorization 헤더로 실어 보낸다(authApi.ts의 signup/login은
// 토큰이 없는 상태에서 호출되므로 이 헤더가 없다 — 그것이 이 파일과의 유일한 차이).
import { ApiError, refresh as refreshTokens } from './authApi';
import { getToken, getRefreshToken, setToken, setRefreshToken } from '../auth/tokenStorage';
import type {
  Asset,
  CreateAssetRequest,
  UpdateHoldingRequest,
  PortfolioResponse,
  SimulateAvgPriceRequest,
  SimulateAvgPriceResponse,
  TokenResponse,
  ErrorResponse,
} from './types';

// 동시에 여러 요청이 401을 맞아도 refresh 요청은 1번만 나가야 한다(백엔드가 rotation 방식이라
// 두 번째 refresh는 반드시 실패한다). 진행 중인 refresh Promise를 모듈 스코프에서 공유한다.
let refreshPromise: Promise<TokenResponse> | null = null;

function requestRefresh(refreshToken: string): Promise<TokenResponse> {
  if (!refreshPromise) {
    refreshPromise = refreshTokens(refreshToken).finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

async function doFetch<T>(path: string, options: RequestInit): Promise<T> {
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

async function authorizedRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  try {
    return await doFetch<T>(path, options);
  } catch (e) {
    if (!(e instanceof ApiError) || e.code !== 'UNAUTHORIZED') {
      throw e;
    }
    const storedRefreshToken = getRefreshToken();
    if (!storedRefreshToken) {
      throw e;
    }
    let refreshed: TokenResponse;
    try {
      refreshed = await requestRefresh(storedRefreshToken);
    } catch {
      // refresh 실패(만료·폐기·존재하지 않음)는 기존 페이지가 알아듣는 UNAUTHORIZED로 변환한다.
      throw new ApiError('UNAUTHORIZED');
    }
    setToken(refreshed.accessToken);
    setRefreshToken(refreshed.refreshToken);
    // 재시도는 최대 1회 — doFetch를 직접 호출해 재귀적으로 재시도하지 않는다.
    return doFetch<T>(path, options);
  }
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
