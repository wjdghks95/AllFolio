import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AssetDetailPage from './AssetDetailPage';
import { VALIDATION_MESSAGES } from '../lib/messages';

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
    <MemoryRouter initialEntries={[{ pathname: `/assets/${id}` }]}>
      <Routes>
        <Route path="/assets/:id" element={<AssetDetailPage />} />
        <Route path="/portfolio" element={<PortfolioFlashProbe />} />
      </Routes>
    </MemoryRouter>,
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

describe('AssetDetailPage', () => {
  it('상세 정보(수량·평단가·취득원가)를 렌더한다', () => {
    renderAssetDetailPage(SAMSUNG_ID);

    expect(screen.getByText('삼성전자')).toBeTruthy();
    expect(screen.getByTestId('asset-detail-quantity').textContent).toContain('10');
    expect(screen.getByTestId('asset-detail-avg-price').textContent).toContain('60,000');
    expect(screen.getByTestId('asset-detail-cost').textContent).toContain('600,000');
  });

  it('id/assetId가 다른 자산(BTC)에서도 병합된 취득원가를 코인 스케일(8)로 렌더한다', () => {
    renderAssetDetailPage(BTC_ID);

    expect(screen.getByText('비트코인')).toBeTruthy();
    expect(screen.getByTestId('asset-detail-quantity').textContent).toContain('0.05123456');
    expect(screen.getByTestId('asset-detail-avg-price').textContent).toContain('82,000,000');
    expect(screen.getByTestId('asset-detail-cost').textContent).toContain('4,201,233.92000000');
  });

  it('평가금액·평가손익·비중 등 null 필드는 "—"로 표시한다', () => {
    renderAssetDetailPage(SAMSUNG_ID);

    expect(screen.getByTestId('asset-detail-evaluation').textContent).toContain('—');
    expect(screen.getByTestId('asset-detail-pnl').textContent).toContain('—');
    expect(screen.getByTestId('asset-detail-weight').textContent).toContain('—');
  });

  it('존재하지 않는 id로 접근하면 안내 카드만 렌더하고 나머지 섹션은 렌더하지 않는다', () => {
    renderAssetDetailPage('no-such-id');

    // not-found 분기에도 문서 제목 계층(h1)이 있어야 한다 — 정상 경로의 자산명 h1과
    // 같은 역할을 하는 고정 제목.
    expect(screen.getByRole('heading', { level: 1, name: '자산 상세' })).toBeTruthy();
    expect(screen.getByTestId('asset-detail-not-found')).toBeTruthy();
    expect(screen.queryByTestId('asset-detail-info')).toBeNull();
    expect(screen.queryByTestId('asset-detail-simulator')).toBeNull();
    expect(screen.queryByTestId('asset-detail-edit')).toBeNull();
  });

  it('CASH 자산에서는 시뮬레이터 Card와 상세/수정 폼의 평단가 칸이 렌더되지 않는다', () => {
    renderAssetDetailPage(CASH_ID);

    expect(screen.queryByTestId('asset-detail-simulator')).toBeNull();
    expect(screen.queryByTestId('asset-detail-edit-avg-price')).toBeNull();
    // docs/DESIGN.md §6-4: CASH는 평단가에 딸린 표시(평단가 행·취득원가 행·평단가 차트 카드)를
    // 전부 내린다 — 위 두 단언은 그중 일부일 뿐이라 나머지 셋도 함께 고정한다.
    expect(screen.queryByTestId('asset-detail-avg-price')).toBeNull();
    expect(screen.queryByTestId('asset-detail-cost')).toBeNull();
    expect(screen.queryByTestId('asset-detail-chart')).toBeNull();
  });

  it('물타기 시뮬레이터 계산 결과가 표시된다 (골든 케이스: 60,000원 10주 + 55,000원 5주 → 58,333원)', () => {
    renderAssetDetailPage(SAMSUNG_ID);

    fireEvent.change(screen.getByTestId('asset-detail-sim-price'), {
      target: { value: '55000' },
    });
    fireEvent.change(screen.getByTestId('asset-detail-sim-quantity'), {
      target: { value: '5' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-sim-calculate'));

    expect(screen.getByTestId('asset-detail-sim-expected-price').textContent).toContain('58,333');
  });

  it('결과가 있는 상태에서 재계산이 검증에 실패하면 이전 결과가 화면에서 지워진다', () => {
    renderAssetDetailPage(SAMSUNG_ID);

    fireEvent.change(screen.getByTestId('asset-detail-sim-price'), {
      target: { value: '55000' },
    });
    fireEvent.change(screen.getByTestId('asset-detail-sim-quantity'), {
      target: { value: '5' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-sim-calculate'));
    expect(screen.getByTestId('asset-detail-sim-expected-price').textContent).toContain('58,333');

    // 단가를 0으로 바꿔 재계산을 시도하면 PRICE_NOT_POSITIVE 에러가 나야 하고, 이전 계산 결과는
    // 더 이상 이 입력에 대한 답이 아니므로 함께 사라져야 한다(§6-4 "결과는 입력이 아니라 답이다").
    fireEvent.change(screen.getByTestId('asset-detail-sim-price'), {
      target: { value: '0' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-sim-calculate'));

    expect(screen.getByText(VALIDATION_MESSAGES.PRICE_NOT_POSITIVE)).toBeTruthy();
    expect(screen.queryByTestId('asset-detail-sim-expected-price')).toBeNull();
    expect(screen.queryByTestId('asset-detail-chart-expected')).toBeNull();
  });

  it('결과가 있는 상태에서 입력값만 바꾸면(재계산 없이) 이전 결과가 즉시 사라진다', () => {
    renderAssetDetailPage(SAMSUNG_ID);

    fireEvent.change(screen.getByTestId('asset-detail-sim-price'), {
      target: { value: '55000' },
    });
    fireEvent.change(screen.getByTestId('asset-detail-sim-quantity'), {
      target: { value: '5' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-sim-calculate'));
    expect(screen.getByTestId('asset-detail-sim-expected-price').textContent).toContain('58,333');

    fireEvent.change(screen.getByTestId('asset-detail-sim-quantity'), {
      target: { value: '7' },
    });

    expect(screen.queryByTestId('asset-detail-sim-expected-price')).toBeNull();
  });

  it('수정 폼 검증에 실패하면 에러 메시지를 보여준다', () => {
    renderAssetDetailPage(SAMSUNG_ID);

    fireEvent.change(screen.getByTestId('asset-detail-edit-avg-price'), {
      target: { value: '0' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-edit-submit'));

    expect(screen.getByText(VALIDATION_MESSAGES.PRICE_NOT_POSITIVE)).toBeTruthy();
  });

  it('수정에 성공하면 flash와 함께 /portfolio로 이동한다', async () => {
    renderAssetDetailPage(SAMSUNG_ID);

    fireEvent.change(screen.getByTestId('asset-detail-edit-quantity'), {
      target: { value: '12' },
    });
    fireEvent.click(screen.getByTestId('asset-detail-edit-submit'));

    await waitFor(() => expect(screen.getByTestId('portfolio-page')).toBeTruthy());
    expect(screen.getByTestId('portfolio-page').textContent).toBe('자산이 수정되었습니다.');
  });

  it('삭제 확인 다이얼로그를 취소하면 화면에 그대로 남는다', () => {
    renderAssetDetailPage(SAMSUNG_ID);

    fireEvent.click(screen.getByTestId('asset-detail-delete-open'));
    fireEvent.click(screen.getByTestId('asset-detail-delete-dialog-cancel'));

    expect(screen.getByTestId('asset-detail-info')).toBeTruthy();
    expect(screen.queryByTestId('portfolio-page')).toBeNull();
  });

  it('삭제를 확인하면 flash와 함께 /portfolio로 이동한다', async () => {
    renderAssetDetailPage(SAMSUNG_ID);

    fireEvent.click(screen.getByTestId('asset-detail-delete-open'));
    fireEvent.click(screen.getByTestId('asset-detail-delete-dialog-confirm'));

    await waitFor(() => expect(screen.getByTestId('portfolio-page')).toBeTruthy());
    expect(screen.getByTestId('portfolio-page').textContent).toBe('자산이 삭제되었습니다.');
  });
});

// simulateAvgPrice(lib/simulate.ts)는 총수량(보유+추가)이 0이면 RangeError를 던진다 — "페이지가
// 먼저 막아야 하는 입력"이라는 뜻이다. 그런데 validateQuantity는 추가 수량 0을 허용하므로
// 보유수량 0인 자산에서만 재현된다. 기존 5개 fixture에는 보유수량 0인 STOCK/COIN이 없어
// fixtures 모듈을 목으로 바꿔치기한다(PortfolioPage.test.tsx의 「표시 계약 회귀」 패턴 응용).
describe('AssetDetailPage 시뮬레이터 0수량 가드 회귀', () => {
  afterEach(() => {
    vi.doUnmock('../api/fixtures');
    vi.resetModules();
  });

  it('보유수량 0 자산에 추가수량 0을 입력하고 계산해도 화면이 죽지 않고 결과가 비어 있다', async () => {
    // 가드가 없으면 lib/simulate.ts의 RangeError가 이벤트 핸들러 밖으로 새어 나가 window의
    // 'error' 이벤트로 잡힌다(jsdom도 브라우저와 동일하게 미처리 예외를 여기로 보고한다).
    // queryByTestId(...).toBeNull() 단언만으로는 React가 트리를 언마운트하지 않아 가드를 지워도
    // 통과해버린다 — 실제로 예외가 0건인지까지 확인해야 이 가드의 회귀를 잡는다.
    const errors: Event[] = [];
    const onError = (e: Event) => {
      errors.push(e);
    };
    window.addEventListener('error', onError);

    vi.resetModules();
    vi.doMock('../api/fixtures', () => ({
      assetListFixture: {
        items: [
          {
            id: 'zero-qty-stock',
            ticker: 'ZERO',
            name: '수량 0 종목',
            assetType: 'STOCK',
            currency: 'KRW',
            quantity: '0',
            avgPrice: '10000',
            version: 0,
            updatedAt: '2026-08-17T09:00:00Z',
          },
        ],
        nextCursor: null,
      },
      portfolioFixture: {
        items: [
          {
            assetId: 'zero-qty-stock',
            ticker: 'ZERO',
            name: '수량 0 종목',
            assetType: 'STOCK',
            currency: 'KRW',
            quantity: '0',
            avgPrice: '10000',
            cost: '0',
            evaluationKrw: null,
            unrealizedPnl: null,
            weight: null,
          },
        ],
        totalCostByCurrency: { KRW: '0' },
        totalEvaluationKrw: null,
        totalUnrealizedPnl: null,
      },
    }));

    try {
      const { default: MockedAssetDetailPage } = await import('./AssetDetailPage');
      render(
        <MemoryRouter initialEntries={['/assets/zero-qty-stock']}>
          <Routes>
            <Route path="/assets/:id" element={<MockedAssetDetailPage />} />
          </Routes>
        </MemoryRouter>,
      );

      fireEvent.change(screen.getByTestId('asset-detail-sim-price'), {
        target: { value: '1000' },
      });
      fireEvent.change(screen.getByTestId('asset-detail-sim-quantity'), {
        target: { value: '0' },
      });
      fireEvent.click(screen.getByTestId('asset-detail-sim-calculate'));

      expect(screen.queryByTestId('asset-detail-sim-expected-price')).toBeNull();
      expect(errors).toHaveLength(0);
    } finally {
      window.removeEventListener('error', onError);
    }
  });
});

// not-found 판정은 asset 존재 여부만 봐야 한다(자산은 있는데 portfolioItem만 없어도 정상
// 렌더돼야 한다) — 그런데 기존 5개 fixture는 assetListFixture/portfolioFixture에 전부 1:1로
// 존재해 `if (!asset || !portfolioItem)`로 되돌려도 이 회귀를 못 잡는다. portfolioFixture.items를
// 빈 배열로 바꿔치기해 그 조합을 강제로 만든다(0수량 가드 회귀와 같은 vi.doMock 패턴).
describe('AssetDetailPage portfolioItem 부재 회귀', () => {
  afterEach(() => {
    vi.doUnmock('../api/fixtures');
    vi.resetModules();
  });

  it('portfolioItem이 없어도 not-found로 빠지지 않고, 파생 필드만 "—"로 표시된다', async () => {
    vi.resetModules();
    vi.doMock('../api/fixtures', () => ({
      assetListFixture: {
        items: [
          {
            id: SAMSUNG_ID,
            ticker: '005930',
            name: '삼성전자',
            assetType: 'STOCK',
            currency: 'KRW',
            quantity: '10',
            avgPrice: '60000',
            version: 0,
            updatedAt: '2026-08-17T09:00:00Z',
          },
        ],
        nextCursor: null,
      },
      portfolioFixture: {
        items: [],
        totalCostByCurrency: {},
        totalEvaluationKrw: null,
        totalUnrealizedPnl: null,
      },
    }));

    const { default: MockedAssetDetailPage } = await import('./AssetDetailPage');
    render(
      <MemoryRouter initialEntries={[`/assets/${SAMSUNG_ID}`]}>
        <Routes>
          <Route path="/assets/:id" element={<MockedAssetDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.queryByTestId('asset-detail-not-found')).toBeNull();
    // 자산 자체(수량·평단가)는 assetListFixture에서 왔으므로 정상 렌더된다.
    expect(screen.getByTestId('asset-detail-quantity').textContent).toContain('10');
    expect(screen.getByTestId('asset-detail-avg-price').textContent).toContain('60,000');
    // portfolioItem 유래 파생 필드(취득원가·평가금액·평가손익·비중)만 NULL_DISPLAY로 빠진다.
    expect(screen.getByTestId('asset-detail-cost').textContent).toContain('—');
    expect(screen.getByTestId('asset-detail-evaluation').textContent).toContain('—');
    expect(screen.getByTestId('asset-detail-pnl').textContent).toContain('—');
    expect(screen.getByTestId('asset-detail-weight').textContent).toContain('—');
  });
});
