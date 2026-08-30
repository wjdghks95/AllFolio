import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AssetNewPage from './AssetNewPage';
import { AuthProvider } from '../auth/AuthProvider';
import type { Asset, ErrorCode } from '../api/types';
import { VALIDATION_MESSAGES } from '../lib/messages';
import { getToken, setToken } from '../auth/tokenStorage';

// PortfolioPage 자체는 이 테스트의 관심사가 아니다 — AssetNewPage가 navigate로 넘기는
// flash state가 실제로 전달되는지만 확인하면 되므로, /portfolio 자리에는 location.state를
// 그대로 노출하는 더미 컴포넌트를 둔다(LoginPage.test.tsx의 더미 라우트 패턴 응용).
function PortfolioFlashProbe() {
  const location = useLocation();
  const state = location.state as { flash?: { tone: string; message: string } } | null;
  return <div data-testid="portfolio-page">{state?.flash?.message}</div>;
}

// POST /v1/assets 성공 응답 골든 값 — 화면이 응답 값을 쓰지 않으므로(navigate만 함)
// 최소한의 유효한 AssetResponse 형태면 충분하다.
const createdAssetFixture: Asset = {
  id: '0198f2a1-0006-7c3a-8f21-000000000006',
  ticker: 'AAPL',
  name: 'Apple Inc.',
  assetType: 'STOCK',
  currency: 'USD',
  quantity: '10',
  avgPrice: '150.25',
  version: 0,
  updatedAt: '2026-08-17T09:00:00Z',
};

function mockCreateSuccess(fetchMock: ReturnType<typeof vi.fn>) {
  fetchMock.mockResolvedValue({ ok: true, json: async () => createdAssetFixture });
}

function mockCreateError(fetchMock: ReturnType<typeof vi.fn>, code: ErrorCode) {
  fetchMock.mockResolvedValue({
    ok: false,
    json: async () => ({ code, message: '오류', timestamp: '2026-08-17T09:00:00Z' }),
  });
}

function renderAssetNewPage() {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={[{ pathname: '/assets/new' }]}>
        <Routes>
          <Route path="/assets/new" element={<AssetNewPage />} />
          <Route path="/portfolio" element={<PortfolioFlashProbe />} />
          <Route path="/login" element={<div data-testid="login-page">로그인</div>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

function fillCommonFields() {
  fireEvent.change(screen.getByTestId('asset-new-ticker'), { target: { value: 'AAPL' } });
  fireEvent.change(screen.getByTestId('asset-new-name'), { target: { value: 'Apple Inc.' } });
  fireEvent.click(screen.getByTestId('asset-new-currency-usd'));
  fireEvent.change(screen.getByTestId('asset-new-quantity'), { target: { value: '10' } });
}

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('AssetNewPage', () => {
  it('필수 입력이 비어 있는 상태로 제출하면 각 필드에 필수 입력 에러가 뜬다', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderAssetNewPage();

    fireEvent.click(screen.getByTestId('asset-new-submit'));

    // 통화는 KRW/USD 선택 UI라 항상 기본값(KRW)이 채워져 있다 — 비워질 수 없다.
    // STOCK(기본 선택)에서 ticker/name/quantity/avgPrice 4개 필드가 모두 비어 있다.
    expect(screen.getAllByText(VALIDATION_MESSAGES.REQUIRED)).toHaveLength(4);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('티커에 공백이 섞여 있으면 공백 에러를 보여준다', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderAssetNewPage();

    fireEvent.change(screen.getByTestId('asset-new-ticker'), { target: { value: 'AB C' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    expect(screen.getByText(VALIDATION_MESSAGES.TICKER_WHITESPACE)).toBeTruthy();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('CASH로 전환하면 평단가 입력란이 사라지고, 나머지 필드만 채워도 등록에 성공한다', async () => {
    setToken('tok');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockCreateSuccess(fetchMock);
    renderAssetNewPage();

    fireEvent.click(screen.getByTestId('asset-new-type-cash'));
    expect(screen.queryByTestId('asset-new-avg-price')).toBeNull();

    fillCommonFields();
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    expect(screen.queryByText(VALIDATION_MESSAGES.PRICE_FORBIDDEN_FOR_CASH)).toBeNull();
    await waitFor(() => expect(screen.getByTestId('portfolio-page')).toBeTruthy());
    expect(screen.getByTestId('portfolio-page').textContent).toBe('자산이 등록되었습니다.');

    // 실제로 보낸 POST /v1/assets 요청 자체를 검증한다 — Authorization 헤더와 CASH의
    // avgPrice: null 제약은 화면에 노출되지 않아 위 flash 단언만으로는 못 잡는다.
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [path, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/v1/assets');
    expect((options.headers as Record<string, string>).Authorization).toBe('Bearer tok');
    expect(JSON.parse(options.body as string).avgPrice).toBeNull();
  });

  it('STOCK에서 평단가를 입력한 뒤 CASH로 전환해도 평단가 값이 리셋되어 정상 제출된다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockCreateSuccess(fetchMock);
    renderAssetNewPage();

    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '71500' } });
    fireEvent.click(screen.getByTestId('asset-new-type-cash'));
    expect(screen.queryByTestId('asset-new-avg-price')).toBeNull();

    fillCommonFields();
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    expect(screen.queryByText(VALIDATION_MESSAGES.PRICE_FORBIDDEN_FOR_CASH)).toBeNull();
    await waitFor(() => expect(screen.getByTestId('portfolio-page')).toBeTruthy());
    expect(screen.getByTestId('portfolio-page').textContent).toBe('자산이 등록되었습니다.');
  });

  it('STOCK 상태에서 평단가가 0이면 양수 에러를 보여준다', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderAssetNewPage();

    fillCommonFields();
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '0' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    expect(screen.getByText(VALIDATION_MESSAGES.PRICE_NOT_POSITIVE)).toBeTruthy();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('평단가 에러가 뜬 채로 CASH로 전환했다가 STOCK으로 복귀하면 이전 평단가 에러는 다시 뜨지 않는다', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderAssetNewPage();

    fillCommonFields();
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '0' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));
    expect(screen.getByText(VALIDATION_MESSAGES.PRICE_NOT_POSITIVE)).toBeTruthy();

    fireEvent.click(screen.getByTestId('asset-new-type-cash'));
    fireEvent.click(screen.getByTestId('asset-new-type-stock'));

    expect(screen.queryByText(VALIDATION_MESSAGES.PRICE_NOT_POSITIVE)).toBeNull();
  });

  it('6개 필드를 모두 유효하게 채우면 성공 flash와 함께 /portfolio로 이동한다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockCreateSuccess(fetchMock);
    renderAssetNewPage();

    fillCommonFields();
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '150.25' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    await waitFor(() => expect(screen.getByTestId('portfolio-page')).toBeTruthy());
    expect(screen.getByTestId('portfolio-page').textContent).toBe('자산이 등록되었습니다.');
  });
});

describe('AssetNewPage 서버 에러·401 처리', () => {
  it('서버 에러(400) 응답이면 폼 상단에 에러 메시지를 보여주고 화면에 남는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockCreateError(fetchMock, 'VALIDATION_ERROR');
    renderAssetNewPage();

    fillCommonFields();
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '150.25' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    await waitFor(() => expect(screen.getByTestId('asset-new-error')).toBeTruthy());
    expect(screen.queryByTestId('portfolio-page')).toBeNull();
  });

  it('401 응답이면 로그아웃(토큰 폐기) 후 로그인 화면으로 이동한다', async () => {
    setToken('tok');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockCreateError(fetchMock, 'UNAUTHORIZED');
    renderAssetNewPage();

    fillCommonFields();
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '150.25' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    await waitFor(() => expect(screen.getByTestId('login-page')).toBeTruthy());
    expect(getToken()).toBeNull();
  });
});
