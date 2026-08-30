// assetApi 전용 테스트. 이 프로젝트는 지금까지 assetApi.ts를 화면 테스트(PortfolioPage.test.tsx 등)의
// fetch 모킹으로 간접 검증해왔다(Task 018 결정). 하지만 401 자동 재시도 로직은 모듈 스코프의
// in-flight refresh Promise 공유(동시성 가드)를 다루므로 화면 테스트로는 검증하기 어려워, 이 로직에
// 한해 별도 파일로 직접 검증한다(Task 019).
import { afterEach, describe, expect, it, vi } from 'vitest';
import { getPortfolio } from './assetApi';
import { getRefreshToken, getToken, setRefreshToken, setToken } from '../auth/tokenStorage';
import type { PortfolioResponse } from './types';

const portfolioFixture: PortfolioResponse = {
  items: [],
  totalCostByCurrency: {},
  totalEvaluationKrw: null,
  totalUnrealizedPnl: null,
};

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

const unauthorizedBody = { code: 'UNAUTHORIZED', message: '만료', timestamp: 't' };
const newTokens = {
  accessToken: 'new-access',
  refreshToken: 'new-refresh',
  tokenType: 'Bearer',
  expiresIn: 900,
};

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('assetApi 401 자동 재시도', () => {
  it('첫 호출이 401이면 refresh 후 원 요청을 새 토큰으로 재시도해 성공한다', async () => {
    setToken('old-access');
    setRefreshToken('old-refresh');

    let portfolioCallCount = 0;
    const fetchMock = vi.fn(async (path: string, _options?: RequestInit) => {
      if (path === '/v1/portfolio') {
        portfolioCallCount += 1;
        return portfolioCallCount === 1 ? jsonResponse(401, unauthorizedBody) : jsonResponse(200, portfolioFixture);
      }
      if (path === '/v1/auth/refresh') {
        return jsonResponse(200, newTokens);
      }
      throw new Error(`unexpected path: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await getPortfolio();

    expect(result).toEqual(portfolioFixture);
    // /v1/portfolio(401) → /v1/auth/refresh(성공) → /v1/portfolio(재시도, 성공) 순서로 정확히 3회.
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[0][0]).toBe('/v1/portfolio');
    const firstCallHeaders = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    expect(firstCallHeaders.Authorization).toBe('Bearer old-access');

    expect(fetchMock.mock.calls[1][0]).toBe('/v1/auth/refresh');
    expect(fetchMock.mock.calls[1][1]).toMatchObject({
      method: 'POST',
      body: JSON.stringify({ refreshToken: 'old-refresh' }),
    });

    expect(fetchMock.mock.calls[2][0]).toBe('/v1/portfolio');
    const retryHeaders = (fetchMock.mock.calls[2][1] as RequestInit).headers as Record<string, string>;
    expect(retryHeaders.Authorization).toBe('Bearer new-access');

    expect(getToken()).toBe('new-access');
    expect(getRefreshToken()).toBe('new-refresh');
  });

  it('동시에 여러 요청이 401을 맞아도 refresh 호출은 1번만 나간다', async () => {
    setToken('old-access');
    setRefreshToken('old-refresh');

    let portfolioCallCount = 0;
    let refreshCallCount = 0;
    const fetchMock = vi.fn(async (path: string, _options?: RequestInit) => {
      if (path === '/v1/portfolio') {
        portfolioCallCount += 1;
        // 두 요청 모두 첫 시도는 401을 맞아야 하므로 처음 2회는 401, 이후(재시도)는 성공.
        return portfolioCallCount <= 2 ? jsonResponse(401, unauthorizedBody) : jsonResponse(200, portfolioFixture);
      }
      if (path === '/v1/auth/refresh') {
        refreshCallCount += 1;
        return jsonResponse(200, newTokens);
      }
      throw new Error(`unexpected path: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    const [r1, r2] = await Promise.all([getPortfolio(), getPortfolio()]);

    expect(r1).toEqual(portfolioFixture);
    expect(r2).toEqual(portfolioFixture);
    expect(refreshCallCount).toBe(1);
    expect(fetchMock.mock.calls.filter((c) => c[0] === '/v1/auth/refresh')).toHaveLength(1);
  });

  it('refresh 자체도 실패하면 최종적으로 ApiError(UNAUTHORIZED)를 던진다', async () => {
    setToken('old-access');
    setRefreshToken('bad-refresh');

    const fetchMock = vi.fn(async (path: string) => {
      if (path === '/v1/portfolio') {
        return jsonResponse(401, unauthorizedBody);
      }
      if (path === '/v1/auth/refresh') {
        return jsonResponse(401, {
          code: 'INVALID_REFRESH_TOKEN',
          message: '유효하지 않은 refresh token 입니다.',
          timestamp: 't',
        });
      }
      throw new Error(`unexpected path: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(getPortfolio()).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('저장된 Refresh Token이 없으면 refresh 시도 없이 바로 ApiError(UNAUTHORIZED)를 던진다', async () => {
    setToken('old-access');
    // setRefreshToken을 호출하지 않아 getRefreshToken()은 null.

    const fetchMock = vi.fn(async (path: string) => {
      if (path === '/v1/portfolio') {
        return jsonResponse(401, unauthorizedBody);
      }
      throw new Error(`unexpected path: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(getPortfolio()).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('재시도한 원 요청마저 401이면 refresh를 다시 시도하지 않고 그대로 ApiError(UNAUTHORIZED)를 던진다', async () => {
    // authorizedRequest가 재시도 시 doFetch를 직접 호출해 재귀적으로 재시도하지 않는다는
    // 불변식(assetApi.ts 73번째 줄 주석)을 직접 검증한다. /v1/portfolio는 재시도해도 항상 401,
    // /v1/auth/refresh는 항상 성공하는 상황에서도 무한 루프에 빠지지 않고 3회 호출에서 멈춰야 한다.
    setToken('old-access');
    setRefreshToken('old-refresh');

    const fetchMock = vi.fn(async (path: string) => {
      if (path === '/v1/portfolio') {
        return jsonResponse(401, unauthorizedBody);
      }
      if (path === '/v1/auth/refresh') {
        return jsonResponse(200, newTokens);
      }
      throw new Error(`unexpected path: ${path}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(getPortfolio()).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    // /v1/portfolio(401) → /v1/auth/refresh(성공) → /v1/portfolio(재시도, 다시 401)에서 멈춘다.
    // 4번째 호출(재-refresh)이 나가면 무한 재시도 회귀다.
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[0][0]).toBe('/v1/portfolio');
    expect(fetchMock.mock.calls[1][0]).toBe('/v1/auth/refresh');
    expect(fetchMock.mock.calls[2][0]).toBe('/v1/portfolio');
  });
});
