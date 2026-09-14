import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AssetDetailPage from './AssetDetailPage';
import { AuthProvider } from '../auth/AuthProvider';
import type {
  Asset,
  CandleBarResponse,
  CandleSeriesResponse,
  ErrorCode,
  PortfolioResponse,
  SimulateAvgPriceResponse,
} from '../api/types';
import { ERROR_MESSAGES, VALIDATION_MESSAGES } from '../lib/messages';
import { getToken, setToken } from '../auth/tokenStorage';

// CandlestickChart는 lightweight-charts(canvas 기반)를 감싸는 컴포넌트라 이 페이지 테스트에서는
// 실제 렌더러 대신 props를 그대로 노출하는 스텁으로 대체한다 — AssetDetailPage가 캔들 데이터·
// 평단선을 "올바르게 전달하는지"(배선)만 검증하고, 캔들이 실제로 그려지는지는
// CandlestickChart.test.tsx(라이브러리 모킹)가 별도로 검증한다(컴포넌트 경계 분리).
vi.mock('../components/CandlestickChart', () => ({
  default: ({
    bars,
    priceLines = [],
    testId,
  }: {
    bars: CandleBarResponse[];
    priceLines?: { id: string; price: string; title: string }[];
    testId?: string;
  }) => (
    <div data-testid={testId}>
      <span data-testid={`${testId}-bar-count`}>{bars.length}</span>
      <span data-testid={`${testId}-bar-last-close`}>{bars.at(-1)?.close ?? ''}</span>
      {priceLines.map((line) => (
        // 뱃지 라벨(title)은 `현재`/`예상` 두 단어로 고정돼 값을 담지 않으므로(docs/DESIGN.md §6-5),
        // 선이 실제로 어느 가격에 그어지는지는 price를 속성으로 노출해 검증한다.
        <span
          key={line.id}
          data-testid={`${testId}-line-${line.id}`}
          data-price={line.price}
        >
          {line.title}
        </span>
      ))}
    </div>
  ),
}));

// PortfolioPage 자체는 이 테스트의 관심사가 아니다 — navigate로 넘기는 flash state가
// 실제로 전달되는지만 확인하면 되므로, /portfolio 자리에는 location.state를 그대로
// 노출하는 더미 컴포넌트를 둔다 (AssetNewPage.test.tsx의 더미 라우트 패턴 응용).
function PortfolioFlashProbe() {
  const location = useLocation();
  const state = location.state as { flash?: { tone: string; message: string } } | null;
  return <div data-testid="portfolio-page">{state?.flash?.message}</div>;
}

function renderAssetDetailPage(id: string) {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={[{ pathname: `/assets/${id}` }]}>
        <Routes>
          <Route path="/assets/:id" element={<AssetDetailPage />} />
          <Route path="/portfolio" element={<PortfolioFlashProbe />} />
          <Route path="/login" element={<div data-testid="login-page">로그인</div>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

// 삼성전자(STOCK/KRW) — 수량 10, 평단가 60,000원. ROADMAP 골든 케이스(10주 × 60,000원 +
// 5주 × 55,000원 → 58,333원)와 현재 보유 수치가 정확히 일치한다.
const SAMSUNG_ID = '0198f2a1-0001-7c3a-8f21-000000000001';
// 비트코인(COIN/KRW) — 수량 0.05123456, 평단가 82,000,000원, 취득원가 4,201,233.92000000
// (scale 8). id/assetId 매칭과 통화별(코인) 스케일 표시를 함께 고정하는 회귀 케이스용.
const BTC_ID = '0198f2a1-0003-7c3a-8f21-000000000003';
// 원화 예수금(CASH) — avgPrice는 서버가 강제 삽입한 1 고정값(ROADMAP 결정 #1).
const CASH_ID = '0198f2a1-0004-7c3a-8f21-000000000004';

const samsungFixture: Asset = {
  id: SAMSUNG_ID,
  ticker: '005930',
  name: '삼성전자',
  assetType: 'STOCK',
  currency: 'KRW',
  quantity: '10',
  avgPrice: '60000',
  version: 0,
  updatedAt: '2026-08-17T09:00:00Z',
};

const btcFixture: Asset = {
  id: BTC_ID,
  ticker: 'BTC',
  name: '비트코인',
  assetType: 'COIN',
  currency: 'KRW',
  quantity: '0.05123456',
  avgPrice: '82000000',
  version: 0,
  updatedAt: '2026-08-17T09:00:00Z',
};

const cashFixture: Asset = {
  id: CASH_ID,
  ticker: 'KRW',
  name: '원화 예수금',
  assetType: 'CASH',
  currency: 'KRW',
  quantity: '1500000',
  avgPrice: '1',
  version: 0,
  updatedAt: '2026-08-17T09:00:00Z',
};

function portfolioResponseFor(item: PortfolioResponse['items'][number] | null): PortfolioResponse {
  return {
    items: item ? [item] : [],
    totalCostByCurrency: {},
    totalEvaluationKrw: null,
    totalUnrealizedPnl: null,
  };
}

const samsungPortfolioItem = {
  assetId: SAMSUNG_ID,
  ticker: '005930',
  name: '삼성전자',
  assetType: 'STOCK' as const,
  currency: 'KRW',
  quantity: '10',
  avgPrice: '60000',
  cost: '600000',
  evaluationKrw: null,
  unrealizedPnl: null,
  weight: null,
};

const btcPortfolioItem = {
  assetId: BTC_ID,
  ticker: 'BTC',
  name: '비트코인',
  assetType: 'COIN' as const,
  currency: 'KRW',
  quantity: '0.05123456',
  avgPrice: '82000000',
  cost: '4201233.92000000',
  evaluationKrw: null,
  unrealizedPnl: null,
  weight: null,
};

const emptyCandles: CandleSeriesResponse = { bars: [], hasMoreHistory: false };

// GET /v1/assets/{id}, GET /v1/portfolio, GET /v1/assets/{id}/candles 세 요청을 순서와 무관하게
// 각각 라우팅한다 — authorizedRequest는 fetch(path, ...)로 호출하므로 path만 보고 분기하면 된다.
// candles는 asset과 URL 접두어가 같아(둘 다 /v1/assets/로 시작) 정규식으로 구분한다.
function mockGetRoutes(
  fetchMock: ReturnType<typeof vi.fn>,
  routes: {
    asset?: Asset | ErrorCode;
    portfolio?: PortfolioResponse | ErrorCode;
    // 지정하지 않으면(대다수 테스트) 빈 캔들 응답으로 기본 처리한다 — 이 화면은 CASH가 아닌 한
    // 항상 캔들을 조회하므로, 캔들 자체가 관심사가 아닌 기존 테스트까지 매번 override할 필요가 없다.
    candles?: CandleSeriesResponse | ErrorCode | ((url: URL) => CandleSeriesResponse | ErrorCode);
  },
) {
  fetchMock.mockImplementation((path: string, options?: RequestInit) => {
    if (!options?.method || options.method === 'GET') {
      const url = new URL(path, 'http://localhost');
      if (/^\/v1\/assets\/[^/]+$/.test(url.pathname) && routes.asset !== undefined) {
        if (typeof routes.asset === 'string') {
          return Promise.resolve({
            ok: false,
            json: async () => ({
              code: routes.asset,
              message: '오류',
              timestamp: '2026-08-17T09:00:00Z',
            }),
          });
        }
        return Promise.resolve({ ok: true, json: async () => routes.asset });
      }
      if (path === '/v1/portfolio' && routes.portfolio !== undefined) {
        if (typeof routes.portfolio === 'string') {
          return Promise.resolve({
            ok: false,
            json: async () => ({
              code: routes.portfolio,
              message: '오류',
              timestamp: '2026-08-17T09:00:00Z',
            }),
          });
        }
        return Promise.resolve({ ok: true, json: async () => routes.portfolio });
      }
      if (/^\/v1\/assets\/[^/]+\/candles$/.test(url.pathname)) {
        const resolved =
          typeof routes.candles === 'function' ? routes.candles(url) : (routes.candles ?? emptyCandles);
        if (typeof resolved === 'string') {
          return Promise.resolve({
            ok: false,
            json: async () => ({
              code: resolved,
              message: '오류',
              timestamp: '2026-08-17T09:00:00Z',
            }),
          });
        }
        return Promise.resolve({ ok: true, json: async () => resolved });
      }
    }
    return Promise.reject(new Error(`unexpected fetch: ${path}`));
  });
}

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('AssetDetailPage', () => {
  it('상세 정보(수량·평단가·취득원가)를 렌더한다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByText('삼성전자')).toBeTruthy());
    expect(screen.getByTestId('asset-detail-quantity').textContent).toContain('10');
    expect(screen.getByTestId('asset-detail-avg-price').textContent).toContain('60,000');
    expect(screen.getByTestId('asset-detail-cost').textContent).toContain('600,000');
  });

  it('id/assetId가 다른 자산(BTC)에서도 병합된 취득원가를 코인 스케일(8)로 렌더한다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: btcFixture,
      portfolio: portfolioResponseFor(btcPortfolioItem),
    });
    renderAssetDetailPage(BTC_ID);

    await waitFor(() => expect(screen.getByText('비트코인')).toBeTruthy());
    expect(screen.getByTestId('asset-detail-quantity').textContent).toContain('0.05123456');
    expect(screen.getByTestId('asset-detail-avg-price').textContent).toContain('82,000,000');
    expect(screen.getByTestId('asset-detail-cost').textContent).toContain('4,201,233.92');
  });

  it('평가금액·평가손익·비중 등 null 필드는 "—"로 표시한다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-evaluation')).toBeTruthy());
    expect(screen.getByTestId('asset-detail-evaluation').textContent).toContain('—');
    expect(screen.getByTestId('asset-detail-pnl').textContent).toContain('—');
    expect(screen.getByTestId('asset-detail-weight').textContent).toContain('—');
  });

  it('존재하지 않는 id로 접근하면 안내 카드만 렌더하고 나머지 섹션은 렌더하지 않는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: 'ASSET_NOT_FOUND',
      portfolio: portfolioResponseFor(null),
    });
    renderAssetDetailPage('no-such-id');

    // not-found 분기에도 문서 제목 계층(h1)이 있어야 한다 — 정상 경로의 자산명 h1과
    // 같은 역할을 하는 고정 제목.
    await waitFor(() => expect(screen.getByTestId('asset-detail-not-found')).toBeTruthy());
    expect(screen.getByRole('heading', { level: 1, name: '자산 상세' })).toBeTruthy();
    expect(screen.queryByTestId('asset-detail-info')).toBeNull();
    expect(screen.queryByTestId('asset-detail-simulator')).toBeNull();
    expect(screen.queryByTestId('asset-detail-edit')).toBeNull();
  });

  it('CASH 자산에서는 시뮬레이터 Card와 상세/수정 폼의 평단가 칸이 렌더되지 않는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: cashFixture,
      portfolio: portfolioResponseFor(null),
    });
    renderAssetDetailPage(CASH_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-info')).toBeTruthy());
    expect(screen.queryByTestId('asset-detail-simulator')).toBeNull();
    expect(screen.queryByTestId('asset-detail-edit-avg-price')).toBeNull();
    // docs/DESIGN.md §6-4: CASH는 평단가에 딸린 표시(평단가 행·취득원가 행·평단가 차트 카드)를
    // 전부 내린다 — 위 두 단언은 그중 일부일 뿐이라 나머지 셋도 함께 고정한다.
    expect(screen.queryByTestId('asset-detail-avg-price')).toBeNull();
    expect(screen.queryByTestId('asset-detail-cost')).toBeNull();
    expect(screen.queryByTestId('asset-detail-chart')).toBeNull();
  });

  it('물타기 시뮬레이터 계산 결과가 표시된다 (골든 케이스: 60,000원 10주 + 55,000원 5주 → 58,333원)', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-simulator')).toBeTruthy());

    const simulateResponse: SimulateAvgPriceResponse = {
      currentAvgPrice: '60000',
      expectedAvgPrice: '58333',
      expectedQuantity: '15.00000000',
      currentWeight: null,
      expectedWeight: null,
      calculatedAt: '2026-08-17T09:00:00Z',
    };
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({ ok: true, json: async () => simulateResponse }),
    );

    fireEvent.change(screen.getByTestId('asset-detail-sim-price'), {
      target: { value: '55000' },
    });
    fireEvent.change(screen.getByTestId('asset-detail-sim-quantity'), {
      target: { value: '5' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-sim-calculate'));

    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-sim-expected-price').textContent).toContain(
        '58,333',
      ),
    );
  });

  it('추가 매수 수량에 0을 입력하고 계산하면 시뮬레이터 API를 호출하지 않고 에러를 보여준다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-simulator')).toBeTruthy());
    const callsBefore = fetchMock.mock.calls.length;

    fireEvent.change(screen.getByTestId('asset-detail-sim-price'), {
      target: { value: '55000' },
    });
    fireEvent.change(screen.getByTestId('asset-detail-sim-quantity'), {
      target: { value: '0' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-sim-calculate'));

    expect(screen.getByText(VALIDATION_MESSAGES.QUANTITY_NOT_POSITIVE)).toBeTruthy();
    expect(fetchMock.mock.calls.length).toBe(callsBefore);
  });

  it('결과가 있는 상태에서 재계산이 검증에 실패하면 이전 결과가 화면에서 지워진다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-simulator')).toBeTruthy());

    const simulateResponse: SimulateAvgPriceResponse = {
      currentAvgPrice: '60000',
      expectedAvgPrice: '58333',
      expectedQuantity: '15.00000000',
      currentWeight: null,
      expectedWeight: null,
      calculatedAt: '2026-08-17T09:00:00Z',
    };
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({ ok: true, json: async () => simulateResponse }),
    );

    fireEvent.change(screen.getByTestId('asset-detail-sim-price'), {
      target: { value: '55000' },
    });
    fireEvent.change(screen.getByTestId('asset-detail-sim-quantity'), {
      target: { value: '5' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-sim-calculate'));
    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-sim-expected-price').textContent).toContain(
        '58,333',
      ),
    );

    // 단가를 0으로 바꿔 재계산을 시도하면 PRICE_NOT_POSITIVE 에러가 나야 하고, 이전 계산 결과는
    // 더 이상 이 입력에 대한 답이 아니므로 함께 사라져야 한다(§6-4 "결과는 입력이 아니라 답이다").
    fireEvent.change(screen.getByTestId('asset-detail-sim-price'), {
      target: { value: '0' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-sim-calculate'));

    expect(screen.getByText(VALIDATION_MESSAGES.PRICE_NOT_POSITIVE)).toBeTruthy();
    expect(screen.queryByTestId('asset-detail-sim-expected-price')).toBeNull();
    expect(screen.queryByTestId('asset-detail-chart-canvas-line-expected')).toBeNull();
  });

  it('결과가 있는 상태에서 입력값만 바꾸면(재계산 없이) 이전 결과가 즉시 사라진다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-simulator')).toBeTruthy());

    const simulateResponse: SimulateAvgPriceResponse = {
      currentAvgPrice: '60000',
      expectedAvgPrice: '58333',
      expectedQuantity: '15.00000000',
      currentWeight: null,
      expectedWeight: null,
      calculatedAt: '2026-08-17T09:00:00Z',
    };
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({ ok: true, json: async () => simulateResponse }),
    );

    fireEvent.change(screen.getByTestId('asset-detail-sim-price'), {
      target: { value: '55000' },
    });
    fireEvent.change(screen.getByTestId('asset-detail-sim-quantity'), {
      target: { value: '5' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-sim-calculate'));
    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-sim-expected-price').textContent).toContain(
        '58,333',
      ),
    );

    fireEvent.change(screen.getByTestId('asset-detail-sim-quantity'), {
      target: { value: '7' },
    });

    expect(screen.queryByTestId('asset-detail-sim-expected-price')).toBeNull();
  });

  it('수정 폼 검증에 실패하면 에러 메시지를 보여준다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-edit')).toBeTruthy());

    fireEvent.change(screen.getByTestId('asset-detail-edit-avg-price'), {
      target: { value: '0' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-edit-submit'));

    expect(screen.getByText(VALIDATION_MESSAGES.PRICE_NOT_POSITIVE)).toBeTruthy();
  });

  it('수정에 성공하면 flash와 함께 /portfolio로 이동한다', async () => {
    setToken('tok');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-edit')).toBeTruthy());

    const updatedAsset: Asset = { ...samsungFixture, quantity: '12', version: 1 };
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({ ok: true, json: async () => updatedAsset }),
    );

    fireEvent.change(screen.getByTestId('asset-detail-edit-quantity'), {
      target: { value: '12' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-edit-submit'));

    await waitFor(() => expect(screen.getByTestId('portfolio-page')).toBeTruthy());
    expect(screen.getByTestId('portfolio-page').textContent).toBe('자산이 수정되었습니다.');

    // 실제로 보낸 PUT /v1/assets/{id}/holdings 요청 자체를 검증한다 — 화면에 노출되지 않는
    // Authorization 헤더·HTTP 메서드·version(낙관적 잠금)이 조용히 깨져도 위 단언만으로는
    // 못 잡는다(뮤테이션 테스트로 실증됨).
    const putCall = fetchMock.mock.calls.find(
      (call: unknown[]) => (call[1] as RequestInit | undefined)?.method === 'PUT',
    );
    expect(putCall).toBeTruthy();
    const [path, options] = putCall as [string, RequestInit];
    expect(path).toBe(`/v1/assets/${SAMSUNG_ID}/holdings`);
    expect((options.headers as Record<string, string>).Authorization).toBe('Bearer tok');
    expect(JSON.parse(options.body as string).version).toBe(samsungFixture.version);
  });

  it('수정 중 409 HOLDING_CONFLICT를 받으면 폼 상단에 안내를 보여주고 화면에 남는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-edit')).toBeTruthy());

    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({
        ok: false,
        json: async () => ({
          code: 'HOLDING_CONFLICT',
          message: '오류',
          timestamp: '2026-08-17T09:00:00Z',
        }),
      }),
    );

    fireEvent.change(screen.getByTestId('asset-detail-edit-quantity'), {
      target: { value: '12' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-edit-submit'));

    await waitFor(() => expect(screen.getByTestId('asset-detail-edit-error')).toBeTruthy());
    expect(screen.getByTestId('asset-detail-edit-error').textContent).toContain(
      ERROR_MESSAGES.HOLDING_CONFLICT,
    );
    expect(screen.queryByTestId('portfolio-page')).toBeNull();
  });

  it('삭제 확인 다이얼로그를 취소하면 화면에 그대로 남는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-info')).toBeTruthy());

    fireEvent.click(screen.getByTestId('asset-detail-delete-open'));
    fireEvent.click(screen.getByTestId('asset-detail-delete-dialog-cancel'));

    expect(screen.getByTestId('asset-detail-info')).toBeTruthy();
    expect(screen.queryByTestId('portfolio-page')).toBeNull();
  });

  it('삭제를 확인하면 flash와 함께 /portfolio로 이동한다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-info')).toBeTruthy());

    // 실제 204 응답은 본문이 없어 res.json()을 호출하면 파싱 에러가 난다 — authorizedRequest가
    // status===204 분기에서 body를 읽지 않고 곧장 반환하는지를 이 json() 실패로 검증한다
    // (분기를 지워도 이전에는 json: async () => undefined라 통과해버리는 구멍이 있었다).
    fetchMock.mockImplementationOnce(() =>
      Promise.resolve({
        ok: true,
        status: 204,
        json: async () => {
          throw new Error('Unexpected end of JSON input');
        },
      }),
    );

    fireEvent.click(screen.getByTestId('asset-detail-delete-open'));
    fireEvent.click(screen.getByTestId('asset-detail-delete-dialog-confirm'));

    await waitFor(() => expect(screen.getByTestId('portfolio-page')).toBeTruthy());
    expect(screen.getByTestId('portfolio-page').textContent).toBe('자산이 삭제되었습니다.');
  });
});

describe('AssetDetailPage 로딩·에러·401 처리', () => {
  it('조회 중에는 불러오는 중 안내를 보여준다', () => {
    const fetchMock = vi.fn(() => new Promise<Response>(() => {}));
    vi.stubGlobal('fetch', fetchMock);
    renderAssetDetailPage(SAMSUNG_ID);

    expect(screen.getByTestId('asset-detail-loading')).toBeTruthy();
  });

  it('자산 조회 실패(500)면 에러 메시지를 보여준다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: 'INTERNAL_ERROR',
      portfolio: portfolioResponseFor(null),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-error')).toBeTruthy());
    expect(screen.getByTestId('asset-detail-error').textContent).toContain(
      ERROR_MESSAGES.INTERNAL_ERROR,
    );
  });

  it('자산 조회가 401이면 로그아웃(토큰 폐기) 후 로그인 화면으로 이동한다', async () => {
    setToken('tok');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: 'UNAUTHORIZED',
      portfolio: portfolioResponseFor(null),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('login-page')).toBeTruthy());
    expect(getToken()).toBeNull();
  });
});

// not-found 판정은 asset 존재 여부만 봐야 한다(자산은 있는데 portfolioItem만 없어도 정상
// 렌더돼야 한다) — GET /v1/portfolio가 실패해도(또는 대상 항목이 없어도) 조용히 무시되고
// asset 자체는 GET /v1/assets/{id}에서 그대로 렌더돼야 한다.
describe('AssetDetailPage portfolioItem 부재 회귀', () => {
  it('GET /v1/portfolio가 실패해도 not-found로 빠지지 않고, 파생 필드만 "—"로 표시된다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: 'INTERNAL_ERROR',
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-info')).toBeTruthy());

    expect(screen.queryByTestId('asset-detail-not-found')).toBeNull();
    // 자산 자체(수량·평단가)는 GET /v1/assets/{id}에서 왔으므로 정상 렌더된다.
    expect(screen.getByTestId('asset-detail-quantity').textContent).toContain('10');
    expect(screen.getByTestId('asset-detail-avg-price').textContent).toContain('60,000');
    // portfolioItem 유래 파생 필드(취득원가·평가금액·평가손익·비중)만 NULL_DISPLAY로 빠진다.
    expect(screen.getByTestId('asset-detail-cost').textContent).toContain('—');
    expect(screen.getByTestId('asset-detail-evaluation').textContent).toContain('—');
    expect(screen.getByTestId('asset-detail-pnl').textContent).toContain('—');
    expect(screen.getByTestId('asset-detail-weight').textContent).toContain('—');
    // 취득원가 등이 "—"인 이유가 GET /v1/portfolio 호출 자체의 실패라는 것을 화면이 밝힌다
    // (대상 항목이 그냥 없는 다음 케이스와 달리 이 케이스만 배너가 떠야 한다).
    expect(screen.getByTestId('asset-detail-portfolio-fetch-failed')).toBeTruthy();
  });

  it('GET /v1/portfolio 응답에 대상 항목이 없어도 not-found로 빠지지 않는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(null),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-info')).toBeTruthy());

    expect(screen.queryByTestId('asset-detail-not-found')).toBeNull();
    expect(screen.getByTestId('asset-detail-cost').textContent).toContain('—');
    // GET /v1/portfolio 자체는 성공했고 단지 대상 항목이 없을 뿐이므로, 호출 실패 배너는
    // 뜨지 않아야 한다(원인 오귀속 방지가 목적이지, 모든 "—"에 배너를 붙이자는 것이 아니다).
    expect(screen.queryByTestId('asset-detail-portfolio-fetch-failed')).toBeNull();
  });
});

function bar(bucketStart: string, price: string): CandleBarResponse {
  return { bucketStart, open: price, high: price, low: price, close: price };
}

describe('AssetDetailPage 캔들 차트(F007, Task 028)', () => {
  it('STOCK 자산 상세는 기본 interval(일)로 캔들을 조회해 차트와 현재 평단선을 렌더한다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const dayBars: CandleSeriesResponse = {
      // 백엔드는 최신순(내림차순)으로 내려준다.
      bars: [bar('2026-09-03', '61000'), bar('2026-09-02', '60500'), bar('2026-09-01', '60000')],
      hasMoreHistory: true,
    };
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
      candles: dayBars,
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count').textContent).toBe('3'),
    );
    const currentLine = screen.getByTestId('asset-detail-chart-canvas-line-current');
    expect(currentLine.textContent).toBe('현재');
    expect(currentLine.getAttribute('data-price')).toBe('60000');
    // 시뮬레이션 결과가 없으면 예상 평단선은 그리지 않는다.
    expect(screen.queryByTestId('asset-detail-chart-canvas-line-expected')).toBeNull();
    // interval 쿼리 파라미터가 기본값(day)으로 실제 요청에 실렸는지 확인한다.
    const candleCall = fetchMock.mock.calls.find((call: unknown[]) =>
      (call[0] as string).includes('/candles'),
    );
    expect((candleCall as unknown[])[0]).toContain('interval=day');
  });

  it('COIN 자산 상세에서도 캔들 차트를 렌더한다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const coinBars: CandleSeriesResponse = {
      bars: [
        bar('2026-09-02T00:00:00Z', '83000000'),
        bar('2026-09-01T00:00:00Z', '82000000'),
      ],
      hasMoreHistory: false,
    };
    mockGetRoutes(fetchMock, {
      asset: btcFixture,
      portfolio: portfolioResponseFor(btcPortfolioItem),
      candles: coinBars,
    });
    renderAssetDetailPage(BTC_ID);

    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count').textContent).toBe('2'),
    );
    // hasMoreHistory가 false면 "과거 더 보기" 버튼이 없어야 한다.
    expect(screen.queryByTestId('asset-detail-chart-load-more')).toBeNull();
  });

  it('기간(interval)을 바꾸면 새 interval로 재조회해 차트를 갱신한다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const dayBars: CandleSeriesResponse = {
      bars: [bar('2026-09-02', '61000'), bar('2026-09-01', '60000')],
      hasMoreHistory: false,
    };
    const weekBars: CandleSeriesResponse = {
      bars: [bar('2026-08-24', '59000')],
      hasMoreHistory: false,
    };
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
      candles: (url) => (url.searchParams.get('interval') === 'week' ? weekBars : dayBars),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count').textContent).toBe('2'),
    );

    fireEvent.click(screen.getByTestId('asset-detail-chart-interval-week'));

    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count').textContent).toBe('1'),
    );
    const weekCall = fetchMock.mock.calls.find((call: unknown[]) =>
      (call[0] as string).includes('interval=week'),
    );
    expect(weekCall).toBeTruthy();
  });

  it('"과거 더 보기"를 누르면 이전 구간을 병합해 보여주고, 더 없으면 버튼이 사라진다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const firstPage: CandleSeriesResponse = {
      bars: [bar('2026-09-02', '61000'), bar('2026-09-01', '60000')],
      hasMoreHistory: true,
    };
    const olderPage: CandleSeriesResponse = {
      bars: [bar('2026-08-31', '59500'), bar('2026-08-30', '59000')],
      hasMoreHistory: false,
    };
    mockGetRoutes(fetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
      // 오름차순으로 뒤집었을 때 가장 오래된 bar(2026-09-01)의 bucketStart가 그대로 before로
      // 넘어와야 한다(ROADMAP Task 028 — 포맷을 다시 만들 필요 없음).
      candles: (url) => (url.searchParams.get('before') === '2026-09-01' ? olderPage : firstPage),
    });
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count').textContent).toBe('2'),
    );
    expect(screen.getByTestId('asset-detail-chart-load-more')).toBeTruthy();

    fireEvent.click(screen.getByTestId('asset-detail-chart-load-more'));

    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count').textContent).toBe('4'),
    );
    // 더 과거 구간이 없다는 응답을 받았으니 버튼은 사라져야 한다.
    expect(screen.queryByTestId('asset-detail-chart-load-more')).toBeNull();
  });

  it('캔들 조회 중에는 로딩 스켈레톤을 보여준다', async () => {
    const fetchMock = vi.fn((path: string) => {
      const url = new URL(path, 'http://localhost');
      if (/^\/v1\/assets\/[^/]+$/.test(url.pathname)) {
        return Promise.resolve({ ok: true, json: async () => samsungFixture });
      }
      if (url.pathname === '/v1/portfolio') {
        return Promise.resolve({
          ok: true,
          json: async () => portfolioResponseFor(samsungPortfolioItem),
        });
      }
      // 캔들 응답은 절대 오지 않는다 — candleState가 'loading'에 머문다.
      return new Promise(() => {});
    });
    vi.stubGlobal('fetch', fetchMock);
    renderAssetDetailPage(SAMSUNG_ID);

    await waitFor(() => expect(screen.getByTestId('asset-detail-chart-loading')).toBeTruthy());
    expect(screen.queryByTestId('asset-detail-chart-canvas')).toBeNull();
  });
});

// SSE(Server-Sent Events) 실시간 연동(Task 028 프론트 8번째 하위 태스크 「프론트엔드 SSE 실시간
// 연동 (COIN)」). happy-dom에는 EventSource 기본 구현이 없다 — 이 프로젝트가 fetch를 모킹하는
// 것과 같은 방식으로 전역에 최소 mock 클래스를 주입한다.
class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  closed = false;
  private listeners: Record<string, ((e: MessageEvent) => void)[]> = {};

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (e: MessageEvent) => void): void {
    (this.listeners[type] ??= []).push(listener);
  }

  removeEventListener(type: string, listener: (e: MessageEvent) => void): void {
    this.listeners[type] = (this.listeners[type] ?? []).filter((l) => l !== listener);
  }

  close(): void {
    this.closed = true;
  }

  // 테스트 전용 헬퍼 — 실제 EventSource에는 없다.
  emit(type: string, data: unknown): void {
    for (const listener of this.listeners[type] ?? []) {
      listener({ data: JSON.stringify(data) } as MessageEvent);
    }
  }
}

describe('AssetDetailPage SSE 실시간 연동(Task 028)', () => {
  afterEach(() => {
    MockEventSource.instances = [];
  });

  it('COIN 자산 상세에서는 candles/stream에 연결하지만, STOCK/CASH에서는 연결하지 않는다', async () => {
    vi.stubGlobal('EventSource', MockEventSource);
    setToken('access-token');

    const coinFetchMock = vi.fn();
    vi.stubGlobal('fetch', coinFetchMock);
    mockGetRoutes(coinFetchMock, {
      asset: btcFixture,
      portfolio: portfolioResponseFor(btcPortfolioItem),
    });
    const { unmount: unmountCoin } = renderAssetDetailPage(BTC_ID);
    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count')).toBeTruthy(),
    );
    expect(MockEventSource.instances).toHaveLength(1);
    const coinStream = MockEventSource.instances[0];
    expect(coinStream.url).toContain(`/v1/assets/${BTC_ID}/candles/stream`);
    expect(coinStream.url).toContain('interval=day');
    expect(coinStream.url).toContain('token=access-token');
    unmountCoin();

    MockEventSource.instances = [];
    const stockFetchMock = vi.fn();
    vi.stubGlobal('fetch', stockFetchMock);
    mockGetRoutes(stockFetchMock, {
      asset: samsungFixture,
      portfolio: portfolioResponseFor(samsungPortfolioItem),
    });
    const { unmount: unmountStock } = renderAssetDetailPage(SAMSUNG_ID);
    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count')).toBeTruthy(),
    );
    expect(MockEventSource.instances).toHaveLength(0);
    unmountStock();

    MockEventSource.instances = [];
    const cashFetchMock = vi.fn();
    vi.stubGlobal('fetch', cashFetchMock);
    mockGetRoutes(cashFetchMock, {
      asset: cashFixture,
      portfolio: portfolioResponseFor(null),
    });
    renderAssetDetailPage(CASH_ID);
    await waitFor(() => expect(screen.getByTestId('asset-detail-info')).toBeTruthy());
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('candle 이벤트 수신 시 같은 bucketStart는 교체하고, 다른 bucketStart는 추가한다', async () => {
    vi.stubGlobal('EventSource', MockEventSource);
    setToken('access-token');

    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const initialBars: CandleSeriesResponse = {
      bars: [bar('2026-09-02T00:00:00Z', '83000000'), bar('2026-09-01T00:00:00Z', '82000000')],
      hasMoreHistory: false,
    };
    mockGetRoutes(fetchMock, {
      asset: btcFixture,
      portfolio: portfolioResponseFor(btcPortfolioItem),
      candles: initialBars,
    });
    renderAssetDetailPage(BTC_ID);

    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count').textContent).toBe('2'),
    );
    expect(MockEventSource.instances).toHaveLength(1);
    const stream = MockEventSource.instances[0];

    // 같은 bucketStart(가장 최근 봉의 갱신) → 개수는 그대로, 교체만 된다.
    stream.emit('candle', bar('2026-09-02T00:00:00Z', '83500000'));
    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count').textContent).toBe('2'),
    );

    // 다른 bucketStart(새 봉 시작) → 개수가 하나 늘어난다.
    stream.emit('candle', bar('2026-09-03T00:00:00Z', '84000000'));
    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count').textContent).toBe('3'),
    );
  });

  it('candle 이벤트가 마지막 bar보다 이른 bucketStart를 보내면 조용히 무시한다(시간 역행 방어, m9)', async () => {
    vi.stubGlobal('EventSource', MockEventSource);
    setToken('access-token');

    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const initialBars: CandleSeriesResponse = {
      bars: [bar('2026-09-02T00:00:00Z', '83000000'), bar('2026-09-01T00:00:00Z', '82000000')],
      hasMoreHistory: false,
    };
    mockGetRoutes(fetchMock, {
      asset: btcFixture,
      portfolio: portfolioResponseFor(btcPortfolioItem),
      candles: initialBars,
    });
    renderAssetDetailPage(BTC_ID);

    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count').textContent).toBe('2'),
    );
    const stream = MockEventSource.instances[0];

    // 마지막 bar(2026-09-02)보다 이른 bucketStart(2026-09-01T12:00) → 배열에 추가·교체 모두 되지
    // 않고 무시돼야 한다(series.setData()가 오름차순을 요구하므로 시간 역행 원소가 섞이면 안 됨).
    stream.emit('candle', bar('2026-09-01T12:00:00Z', '99999999'));

    // 다른 정상 이벤트(늦은 bucketStart)가 처리될 때까지 기다려, 역행 이벤트가 비동기적으로
    // 뒤늦게 반영되지 않았음을 함께 확인한다.
    stream.emit('candle', bar('2026-09-03T00:00:00Z', '84000000'));
    await waitFor(() =>
      expect(screen.getByTestId('asset-detail-chart-canvas-bar-count').textContent).toBe('3'),
    );
    expect(screen.getByTestId('asset-detail-chart-canvas-bar-last-close').textContent).toBe(
      '84000000',
    );
  });

  it('interval 전환 시 이전 연결을 닫고 새 interval로 다시 연결한다', async () => {
    vi.stubGlobal('EventSource', MockEventSource);
    setToken('access-token');

    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: btcFixture,
      portfolio: portfolioResponseFor(btcPortfolioItem),
    });
    renderAssetDetailPage(BTC_ID);

    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1));
    const firstStream = MockEventSource.instances[0];
    expect(firstStream.closed).toBe(false);

    fireEvent.click(screen.getByTestId('asset-detail-chart-interval-week'));

    await waitFor(() => expect(MockEventSource.instances).toHaveLength(2));
    expect(firstStream.closed).toBe(true);
    expect(MockEventSource.instances[1].url).toContain('interval=week');
  });

  it('언마운트 시 EventSource.close()를 호출한다', async () => {
    vi.stubGlobal('EventSource', MockEventSource);
    setToken('access-token');

    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockGetRoutes(fetchMock, {
      asset: btcFixture,
      portfolio: portfolioResponseFor(btcPortfolioItem),
    });
    const { unmount } = renderAssetDetailPage(BTC_ID);

    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1));
    const stream = MockEventSource.instances[0];
    expect(stream.closed).toBe(false);

    unmount();
    expect(stream.closed).toBe(true);
  });
});
