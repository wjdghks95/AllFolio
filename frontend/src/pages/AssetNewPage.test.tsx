import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AssetNewPage from './AssetNewPage';
import { AuthProvider } from '../auth/AuthProvider';
import type { Asset, ErrorCode, SearchResult } from '../api/types';
import { SEARCH_SELECTION_REQUIRED_MESSAGE, VALIDATION_MESSAGES } from '../lib/messages';
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

// GET /v1/assets/search?...&currency=USD 응답 골든 값 — SearchCombobox가 KRW/USD를 병렬
// 호출하므로, USD 쪽에만 결과를 하나 두고 KRW 쪽은 빈 배열로 응답하게 한다(mockSearchAndCreate).
const searchResultFixture: SearchResult = {
  ticker: 'AAPL',
  name: 'Apple Inc.',
  assetType: 'STOCK',
  currency: 'USD',
};

// 검색 호출(/v1/assets/search)과 등록 호출(POST /v1/assets)을 경로로 구분해 응답을 분기한다.
// 검색은 항상 searchResultFixture(USD 쪽)로 성공하고, 등록만 시나리오별로 성공/실패를 바꾼다.
function mockSearchAndCreate(
  fetchMock: ReturnType<typeof vi.fn>,
  createResponse: { ok: boolean; json: () => Promise<unknown> },
) {
  fetchMock.mockImplementation((path: unknown) => {
    const url = String(path);
    if (url.startsWith('/v1/assets/search')) {
      const results = url.includes('currency=USD') ? [searchResultFixture] : [];
      return Promise.resolve({ ok: true, json: async () => results });
    }
    return Promise.resolve(createResponse);
  });
}

function mockCreateSuccess(fetchMock: ReturnType<typeof vi.fn>) {
  mockSearchAndCreate(fetchMock, { ok: true, json: async () => createdAssetFixture });
}

function mockCreateError(fetchMock: ReturnType<typeof vi.fn>, code: ErrorCode) {
  mockSearchAndCreate(fetchMock, {
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

// STOCK(기본 선택) 흐름 전용 — 검색창에 입력 → 디바운스(350ms) 대기 → 첫 결과 옵션 선택 → 수량 입력.
// 호출 전에 fetchMock이 mockCreateSuccess/mockCreateError로 검색·등록 라우팅을 갖추고 있어야 한다.
// 디바운스 대기 구간만 가짜 타이머를 쓰고 즉시 실제 타이머로 되돌린다 — 이후 waitFor(실제 타이머 폴링)와
// 섞이면 폴링이 멈출 수 있어서다.
async function fillCommonFieldsViaSearch() {
  vi.useFakeTimers();
  fireEvent.change(screen.getByTestId('asset-new-symbol-search'), { target: { value: 'Apple' } });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(400);
  });
  vi.useRealTimers();
  fireEvent.click(screen.getByTestId('asset-new-symbol-search-option-0'));
  fireEvent.change(screen.getByTestId('asset-new-quantity'), { target: { value: '10' } });
}

// CASH 흐름 전용 — 검색을 거치지 않고 자유 텍스트 필드 + 통화 토글을 직접 채운다.
function fillCommonFieldsCash() {
  fireEvent.change(screen.getByTestId('asset-new-ticker'), { target: { value: 'AAPL' } });
  fireEvent.change(screen.getByTestId('asset-new-name'), { target: { value: 'Apple Inc.' } });
  fireEvent.click(screen.getByTestId('asset-new-currency-usd'));
  fireEvent.change(screen.getByTestId('asset-new-quantity'), { target: { value: '10' } });
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('AssetNewPage', () => {
  it('필수 입력이 비어 있는 상태로 제출하면 각 필드에 필수 입력 에러가 뜬다', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderAssetNewPage();

    fireEvent.click(screen.getByTestId('asset-new-submit'));

    // STOCK(기본 선택)에서 티커/종목명은 검색 콤보박스 하나가 두 에러를 함께 대표하는데, 그 REQUIRED는
    // "칸이 비었다"가 아니라 "목록에서 고르지 않았다"를 뜻하므로 일반 REQUIRED 문구가 아니라
    // SEARCH_SELECTION_REQUIRED_MESSAGE로 뜬다(ui-ux-designer 결정, docs/DESIGN.md §6-3-1).
    // 수량 + 평단가는 그대로 일반 REQUIRED 문구다.
    expect(screen.getByText(SEARCH_SELECTION_REQUIRED_MESSAGE)).toBeTruthy();
    expect(screen.getAllByText(VALIDATION_MESSAGES.REQUIRED)).toHaveLength(2);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('CASH에서 티커에 공백이 섞여 있으면 공백 에러를 보여준다', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderAssetNewPage();

    // CASH만 티커를 자유 텍스트로 받는다 — STOCK/COIN은 검색 결과 선택이라 이 검증 경로가 없다.
    fireEvent.click(screen.getByTestId('asset-new-type-cash'));
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

    fillCommonFieldsCash();
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

    fillCommonFieldsCash();
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    expect(screen.queryByText(VALIDATION_MESSAGES.PRICE_FORBIDDEN_FOR_CASH)).toBeNull();
    await waitFor(() => expect(screen.getByTestId('portfolio-page')).toBeTruthy());
    expect(screen.getByTestId('portfolio-page').textContent).toBe('자산이 등록되었습니다.');
  });

  it('STOCK 상태에서 평단가가 0이면 양수 에러를 보여준다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockCreateSuccess(fetchMock);
    renderAssetNewPage();

    await fillCommonFieldsViaSearch();
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '0' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    expect(screen.getByText(VALIDATION_MESSAGES.PRICE_NOT_POSITIVE)).toBeTruthy();
    // 검색으로 선택은 이미 끝났으므로 여기서 늘어난 호출은 등록(POST) 시도 뿐이어야 하는데,
    // 검증 실패로 제출 자체가 막히므로 검색 호출(2건: KRW/USD) 이상은 없어야 한다.
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('평단가 에러가 뜬 채로 CASH로 전환했다가 STOCK으로 복귀하면 이전 평단가 에러는 다시 뜨지 않는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockCreateSuccess(fetchMock);
    renderAssetNewPage();

    await fillCommonFieldsViaSearch();
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '0' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));
    expect(screen.getByText(VALIDATION_MESSAGES.PRICE_NOT_POSITIVE)).toBeTruthy();

    fireEvent.click(screen.getByTestId('asset-new-type-cash'));
    fireEvent.click(screen.getByTestId('asset-new-type-stock'));

    expect(screen.queryByText(VALIDATION_MESSAGES.PRICE_NOT_POSITIVE)).toBeNull();
  });

  it('선택 후 검색어를 지우고 제출하면 검색 선택 요구 에러가 뜨고 등록 fetch가 나가지 않는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockCreateSuccess(fetchMock);
    renderAssetNewPage();

    await fillCommonFieldsViaSearch();
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '150.25' } });

    // 선택 후 검색어를 지우면 ticker/name이 리셋된다 — 이전 선택값이 남은 채 제출되면 안 된다.
    // "칸이 비었다"가 아니라 "고르지 않았다"이므로 SEARCH_SELECTION_REQUIRED_MESSAGE가 뜬다.
    fireEvent.change(screen.getByTestId('asset-new-symbol-search'), { target: { value: '' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    expect(screen.getByText(SEARCH_SELECTION_REQUIRED_MESSAGE)).toBeTruthy();
    // 검색 호출(2건: KRW/USD) 이상 늘지 않아야 한다 — 등록(POST) fetch가 나가지 않았다는 뜻.
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('검색어를 한 번도 입력하지 않고 제출해도(타이핑 이력 없음) 검색 선택 요구 에러가 뜬다', () => {
    // ui-ux-designer 명시 결정: "타이핑한 적이 있는지"로 문구를 가르지 않는다 — ticker/name이
    // 비어 있으면(REQUIRED) 타이핑 여부와 무관하게 항상 SEARCH_SELECTION_REQUIRED_MESSAGE다.
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderAssetNewPage();

    fireEvent.change(screen.getByTestId('asset-new-quantity'), { target: { value: '10' } });
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '150.25' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    expect(screen.getByText(SEARCH_SELECTION_REQUIRED_MESSAGE)).toBeTruthy();
    expect(screen.queryByText(VALIDATION_MESSAGES.REQUIRED)).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('6개 필드를 모두 유효하게 채우면 성공 flash와 함께 /portfolio로 이동한다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    mockCreateSuccess(fetchMock);
    renderAssetNewPage();

    await fillCommonFieldsViaSearch();
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

    await fillCommonFieldsViaSearch();
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

    await fillCommonFieldsViaSearch();
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '150.25' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    await waitFor(() => expect(screen.getByTestId('login-page')).toBeTruthy());
    expect(getToken()).toBeNull();
  });
});
