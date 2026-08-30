import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import PortfolioPage from './PortfolioPage';
import { AuthProvider } from '../auth/AuthProvider';
import type { PortfolioResponse, ErrorCode } from '../api/types';
import { ERROR_MESSAGES } from '../lib/messages';
import { getToken, setToken } from '../auth/tokenStorage';

// GET /v1/portfolio 골든 픽스처 — 과거 목 데이터 모듈의 portfolioFixture를 이관(Task 018).
const portfolioResponseFixture: PortfolioResponse = {
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
  totalCostByCurrency: { KRW: '6476733.92', USD: '2190.0000' },
  totalEvaluationKrw: null,
  totalUnrealizedPnl: null,
};

function mockPortfolioResponse(fetchMock: ReturnType<typeof vi.fn>, data: PortfolioResponse) {
  fetchMock.mockResolvedValue({ ok: true, json: async () => data });
}

function mockErrorResponse(fetchMock: ReturnType<typeof vi.fn>, code: ErrorCode) {
  fetchMock.mockResolvedValue({
    ok: false,
    json: async () => ({ code, message: '오류', timestamp: '2026-08-17T09:00:00Z' }),
  });
}

function renderPortfolioPage(
  initialEntries: Array<string | { pathname: string; state?: unknown }> = ['/portfolio'],
) {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={initialEntries}>
        <Routes>
          <Route path="/portfolio" element={<PortfolioPage />} />
          <Route path="/assets/new" element={<div data-testid="assets-new-page">자산 등록</div>} />
          <Route path="/assets/:id" element={<div data-testid="asset-detail-page">자산 상세</div>} />
          <Route path="/login" element={<div data-testid="login-page">로그인</div>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('PortfolioPage', () => {
  it('중복 티커(005930)를 포함해 5개 항목이 모두 별도로 렌더된다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, portfolioResponseFixture);
    renderPortfolioPage();

    await waitFor(() =>
      expect(screen.getAllByTestId(/^portfolio-item-[0-9a-f-]{36}$/)).toHaveLength(5),
    );
    expect(
      screen.getByTestId('portfolio-item-0198f2a1-0001-7c3a-8f21-000000000001'),
    ).toBeTruthy();
    expect(
      screen.getByTestId('portfolio-item-0198f2a1-0002-7c3a-8f21-000000000002'),
    ).toBeTruthy();
  });

  it('투자 자산(STOCK/COIN)과 현금 자산(CASH)이 별도 목록으로 분리된다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, portfolioResponseFixture);
    renderPortfolioPage();

    await waitFor(() => expect(screen.getByTestId('portfolio-investment-list')).toBeTruthy());
    const investmentList = screen.getByTestId('portfolio-investment-list');
    const cashList = screen.getByTestId('portfolio-cash-list');

    expect(
      within(investmentList).getAllByTestId(/^portfolio-item-[0-9a-f-]{36}$/),
    ).toHaveLength(4);
    expect(within(cashList).getAllByTestId(/^portfolio-item-[0-9a-f-]{36}$/)).toHaveLength(1);

    expect(
      within(cashList).getByTestId('portfolio-item-0198f2a1-0004-7c3a-8f21-000000000004'),
    ).toBeTruthy();
    expect(
      within(investmentList).queryByTestId('portfolio-item-0198f2a1-0004-7c3a-8f21-000000000004'),
    ).toBeNull();
  });

  it('evaluationKrw/unrealizedPnl이 null인 항목은 "—"로 표시된다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, portfolioResponseFixture);
    renderPortfolioPage();

    await waitFor(() =>
      expect(
        screen.getByTestId('portfolio-item-0198f2a1-0001-7c3a-8f21-000000000001'),
      ).toBeTruthy(),
    );
    const item = screen.getByTestId('portfolio-item-0198f2a1-0001-7c3a-8f21-000000000001');
    const dashCount = (item.textContent?.match(/—/g) ?? []).length;
    expect(dashCount).toBeGreaterThanOrEqual(2);
  });

  it('요약 영역에 평가금액이 표시된다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, portfolioResponseFixture);
    renderPortfolioPage();

    await waitFor(() => expect(screen.getByTestId('portfolio-summary')).toBeTruthy());
    const summary = screen.getByTestId('portfolio-summary');
    expect(summary.textContent).toContain('평가금액');
    expect(summary.textContent).toContain('—');
  });

  it('"자산 등록" 버튼 클릭 시 /assets/new로 이동한다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, portfolioResponseFixture);
    renderPortfolioPage();

    await waitFor(() => expect(screen.getByTestId('portfolio-add-asset-button')).toBeTruthy());
    fireEvent.click(screen.getByTestId('portfolio-add-asset-button'));

    await waitFor(() => expect(screen.getByTestId('assets-new-page')).toBeTruthy());
  });

  it('자산 행 클릭 시 해당 자산 상세로 이동한다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, portfolioResponseFixture);
    renderPortfolioPage();

    await waitFor(() =>
      expect(
        screen.getByTestId('portfolio-item-0198f2a1-0003-7c3a-8f21-000000000003'),
      ).toBeTruthy(),
    );
    fireEvent.click(
      screen.getByTestId('portfolio-item-0198f2a1-0003-7c3a-8f21-000000000003'),
    );

    await waitFor(() => expect(screen.getByTestId('asset-detail-page')).toBeTruthy());
  });

  it('제목이 "총 자산"으로 표시된다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, portfolioResponseFixture);
    renderPortfolioPage();

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('총 자산'),
    );
  });

  it('목록 행에는 평가금액·손익·수량만 있고 평단가·취득원가·비중은 없다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, portfolioResponseFixture);
    renderPortfolioPage();

    await waitFor(() =>
      expect(
        screen.getByTestId('portfolio-item-0198f2a1-0001-7c3a-8f21-000000000001'),
      ).toBeTruthy(),
    );
    const row = screen.getByTestId('portfolio-item-0198f2a1-0001-7c3a-8f21-000000000001');
    expect(row.textContent).toContain('평가금액');
    expect(row.textContent).toContain('손익');
    expect(row.textContent).toContain('수량');
    expect(row.textContent).not.toContain('평단가');
    expect(row.textContent).not.toContain('취득원가');
    expect(row.textContent).not.toContain('비중');
  });

  it('history state에 flash가 있으면 배너가 뜨고, 화면에 계속 남아 있는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, portfolioResponseFixture);
    renderPortfolioPage([
      {
        pathname: '/portfolio',
        state: { flash: { tone: 'success', message: '자산이 등록되었습니다.' } },
      },
    ]);

    // flash는 첫 렌더 시점에 로컬 state로 한 번만 캡처되므로, 데이터 로딩 상태와 무관하게
    // 곧바로(로딩 중에도) 화면에 남아 있어야 한다(자동 소멸 타이머 없음, Task 007 결정).
    expect(screen.getByTestId('portfolio-flash').textContent).toBe('자산이 등록되었습니다.');
    await waitFor(() => expect(screen.getByTestId('portfolio-summary')).toBeTruthy());
    expect(screen.getByTestId('portfolio-flash').textContent).toBe('자산이 등록되었습니다.');
  });

  it('flash 없이 진입하면 안내 배너를 보여주지 않는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, portfolioResponseFixture);
    renderPortfolioPage();

    await waitFor(() => expect(screen.getByTestId('portfolio-summary')).toBeTruthy());
    expect(screen.queryByTestId('portfolio-flash')).toBeNull();
  });

  it('flash를 실은 채 진입한 뒤 다른 라우트로 나갔다가 뒤로가기로 돌아오면 배너가 다시 뜨지 않는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, portfolioResponseFixture);

    function AssetDetailProbe() {
      const navigate = useNavigate();
      return (
        <div data-testid="asset-detail-page">
          <button type="button" onClick={() => navigate(-1)}>
            뒤로가기
          </button>
        </div>
      );
    }

    render(
      <AuthProvider>
        <MemoryRouter
          initialEntries={[
            {
              pathname: '/portfolio',
              state: { flash: { tone: 'success', message: '자산이 등록되었습니다.' } },
            },
          ]}
        >
          <Routes>
            <Route path="/portfolio" element={<PortfolioPage />} />
            <Route path="/assets/:id" element={<AssetDetailProbe />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>,
    );

    expect(screen.getByTestId('portfolio-flash')).toBeTruthy();

    await waitFor(() =>
      expect(
        screen.getByTestId('portfolio-item-0198f2a1-0001-7c3a-8f21-000000000001'),
      ).toBeTruthy(),
    );
    fireEvent.click(
      screen.getByTestId('portfolio-item-0198f2a1-0001-7c3a-8f21-000000000001'),
    );
    await waitFor(() => expect(screen.getByTestId('asset-detail-page')).toBeTruthy());

    fireEvent.click(screen.getByText('뒤로가기'));
    await waitFor(() => expect(screen.getByTestId('portfolio-add-asset-button')).toBeTruthy());

    expect(screen.queryByTestId('portfolio-flash')).toBeNull();
  });
});

describe('PortfolioPage 로딩·에러·401 처리', () => {
  it('데이터 로딩 중에는 불러오는 중 안내를 보여준다', () => {
    const fetchMock = vi.fn(() => new Promise<Response>(() => {}));
    vi.stubGlobal('fetch', fetchMock);
    renderPortfolioPage();

    expect(screen.getByTestId('portfolio-loading')).toBeTruthy();
  });

  it('조회 실패(500) 시 에러 메시지를 보여준다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockErrorResponse(fetchMock, 'INTERNAL_ERROR');
    renderPortfolioPage();

    await waitFor(() => expect(screen.getByTestId('portfolio-error')).toBeTruthy());
    expect(screen.getByTestId('portfolio-error').textContent).toContain(
      ERROR_MESSAGES.INTERNAL_ERROR,
    );
  });

  it('401 응답이면 로그아웃(토큰 폐기) 후 로그인 화면으로 이동한다', async () => {
    setToken('tok');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockErrorResponse(fetchMock, 'UNAUTHORIZED');
    renderPortfolioPage();

    await waitFor(() => expect(screen.getByTestId('login-page')).toBeTruthy());
    expect(getToken()).toBeNull();
  });
});

// evaluationKrw/unrealizedPnl은 필드명과 무관하게 항상 KRW 환산액이다(ROADMAP 「API 규격」).
// portfolioResponseFixture는 두 필드가 항상 null이라 실제 렌더 케이스를 못 잡으므로, 이 그룹만
// fetch 응답에 non-null 값을 흘려보낸다.
describe('PortfolioPage 표시 계약 회귀', () => {
  it('evaluationKrw/unrealizedPnl은 원자산이 COIN이어도 KRW 스케일(정수)로 표시된다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, {
      items: [
        {
          assetId: 'test-coin-1',
          ticker: 'BTC',
          name: '비트코인',
          assetType: 'COIN',
          currency: 'KRW',
          quantity: '1',
          avgPrice: '1',
          cost: '1',
          // COIN 스케일(8자리)로 잘못 찍히면 소수부가 그대로 남는다. KRW 스케일(정수)이면
          // HALF_UP으로 반올림되어 그룹핑된 정수만 남아야 한다.
          evaluationKrw: '82000000.12345678',
          unrealizedPnl: '1234567.89',
          weight: null,
        },
      ],
      totalCostByCurrency: { KRW: '1' },
      totalEvaluationKrw: null,
      totalUnrealizedPnl: null,
    });
    renderPortfolioPage();

    await waitFor(() => expect(screen.getByTestId('portfolio-item-test-coin-1')).toBeTruthy());
    const row = screen.getByTestId('portfolio-item-test-coin-1');
    expect(row.textContent).toContain('82,000,000');
    expect(row.textContent).not.toContain('82,000,000.12345678');
    expect(row.textContent).toContain('1,234,568');
    expect(row.textContent).not.toContain('1234567.89');
  });

  it('자산이 0건이면 빈 상태 문구와 "총 자산" 제목이 표시된다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockPortfolioResponse(fetchMock, {
      items: [],
      totalCostByCurrency: {},
      totalEvaluationKrw: null,
      totalUnrealizedPnl: null,
    });
    renderPortfolioPage();

    // 빈 상태도 일반 상태와 같은 "총 자산" 제목을 써야 한다.
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('총 자산'),
    );
    expect(screen.getByText('등록된 자산이 없습니다.')).toBeTruthy();
    expect(screen.getByTestId('portfolio-add-asset-button')).toBeTruthy();
  });
});
