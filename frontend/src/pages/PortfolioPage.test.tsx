import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import PortfolioPage from './PortfolioPage';

function renderPortfolioPage() {
  return render(
    <MemoryRouter initialEntries={['/portfolio']}>
      <Routes>
        <Route path="/portfolio" element={<PortfolioPage />} />
        <Route path="/assets/new" element={<div data-testid="assets-new-page">자산 등록</div>} />
        <Route path="/assets/:id" element={<div data-testid="asset-detail-page">자산 상세</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('PortfolioPage', () => {
  it('중복 티커(005930)를 포함해 5개 항목이 모두 별도로 렌더된다', () => {
    renderPortfolioPage();

    // "portfolio-investment-list"/"portfolio-cash-list" 컨테이너까지 매칭되지 않도록
    // UUID 패턴으로 좁힌다(컨테이너 testid는 이 정규식과 형태가 달라 원래도 안 걸리지만,
    // 아이템 testid만 정확히 골라낸다는 의도를 주석으로 남긴다).
    expect(screen.getAllByTestId(/^portfolio-item-[0-9a-f-]{36}$/)).toHaveLength(5);
    expect(
      screen.getByTestId('portfolio-item-0198f2a1-0001-7c3a-8f21-000000000001'),
    ).toBeTruthy();
    expect(
      screen.getByTestId('portfolio-item-0198f2a1-0002-7c3a-8f21-000000000002'),
    ).toBeTruthy();
  });

  it('투자 자산(STOCK/COIN)과 현금 자산(CASH)이 별도 목록으로 분리된다', () => {
    renderPortfolioPage();

    const investmentList = screen.getByTestId('portfolio-investment-list');
    const cashList = screen.getByTestId('portfolio-cash-list');

    // STOCK 3건(삼성전자 2건 + Apple) + COIN 1건(비트코인) = 4건.
    expect(
      within(investmentList).getAllByTestId(/^portfolio-item-[0-9a-f-]{36}$/),
    ).toHaveLength(4);
    // CASH 1건(원화 예수금).
    expect(within(cashList).getAllByTestId(/^portfolio-item-[0-9a-f-]{36}$/)).toHaveLength(1);

    expect(
      within(cashList).getByTestId('portfolio-item-0198f2a1-0004-7c3a-8f21-000000000004'),
    ).toBeTruthy();
    expect(
      within(investmentList).queryByTestId('portfolio-item-0198f2a1-0004-7c3a-8f21-000000000004'),
    ).toBeNull();
  });

  it('evaluationKrw/unrealizedPnl이 null인 항목은 "—"로 표시된다', () => {
    renderPortfolioPage();

    const item = screen.getByTestId('portfolio-item-0198f2a1-0001-7c3a-8f21-000000000001');
    const dashCount = (item.textContent?.match(/—/g) ?? []).length;
    expect(dashCount).toBeGreaterThanOrEqual(2);
  });

  it('요약 영역에 평가금액이 표시된다', () => {
    renderPortfolioPage();

    const summary = screen.getByTestId('portfolio-summary');
    expect(summary.textContent).toContain('평가금액');
    // totalEvaluationKrw는 시세 미연동(Phase 2)이라 null → "—"로 표시된다.
    expect(summary.textContent).toContain('—');
  });

  it('"자산 등록" 버튼 클릭 시 /assets/new로 이동한다', async () => {
    renderPortfolioPage();

    fireEvent.click(screen.getByTestId('portfolio-add-asset-button'));

    await waitFor(() => expect(screen.getByTestId('assets-new-page')).toBeTruthy());
  });

  it('자산 행 클릭 시 해당 자산 상세로 이동한다', async () => {
    renderPortfolioPage();

    fireEvent.click(
      screen.getByTestId('portfolio-item-0198f2a1-0003-7c3a-8f21-000000000003'),
    );

    await waitFor(() => expect(screen.getByTestId('asset-detail-page')).toBeTruthy());
  });

  it('제목이 "총 자산"으로 표시된다', () => {
    renderPortfolioPage();

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('총 자산');
  });

  it('목록 행에는 평가금액·손익·수량만 있고 평단가·취득원가·비중은 없다', () => {
    renderPortfolioPage();

    const row = screen.getByTestId('portfolio-item-0198f2a1-0001-7c3a-8f21-000000000001');
    expect(row.textContent).toContain('평가금액');
    expect(row.textContent).toContain('손익');
    expect(row.textContent).toContain('수량');
    expect(row.textContent).not.toContain('평단가');
    expect(row.textContent).not.toContain('취득원가');
    expect(row.textContent).not.toContain('비중');
  });
});

// evaluationKrw/unrealizedPnl은 필드명과 무관하게 항상 KRW 환산액이다(ROADMAP 「API 규격」).
// portfolioFixture는 두 필드가 항상 null이라 실제 렌더 케이스를 못 잡으므로, 이 그룹만
// fixtures 모듈을 목으로 바꿔치기해 non-null 값을 흘려보낸다.
describe('PortfolioPage 표시 계약 회귀', () => {
  afterEach(() => {
    vi.doUnmock('../api/fixtures');
    vi.resetModules();
  });

  it('evaluationKrw/unrealizedPnl은 원자산이 COIN이어도 KRW 스케일(정수)로 표시된다', async () => {
    vi.resetModules();
    vi.doMock('../api/fixtures', () => ({
      portfolioFixture: {
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
      },
    }));

    const { default: MockedPortfolioPage } = await import('./PortfolioPage');
    render(
      <MemoryRouter initialEntries={['/portfolio']}>
        <Routes>
          <Route path="/portfolio" element={<MockedPortfolioPage />} />
        </Routes>
      </MemoryRouter>,
    );

    const row = screen.getByTestId('portfolio-item-test-coin-1');
    expect(row.textContent).toContain('82,000,000');
    expect(row.textContent).not.toContain('82,000,000.12345678');
    expect(row.textContent).toContain('1,234,568');
    expect(row.textContent).not.toContain('1234567.89');
  });

  it('자산이 0건이면 빈 상태 문구와 "총 자산" 제목이 표시된다', async () => {
    vi.resetModules();
    vi.doMock('../api/fixtures', () => ({
      portfolioFixture: {
        items: [],
        totalCostByCurrency: {},
        totalEvaluationKrw: null,
        totalUnrealizedPnl: null,
      },
    }));

    const { default: MockedPortfolioPage } = await import('./PortfolioPage');
    render(
      <MemoryRouter initialEntries={['/portfolio']}>
        <Routes>
          <Route path="/portfolio" element={<MockedPortfolioPage />} />
        </Routes>
      </MemoryRouter>,
    );

    // 빈 상태도 일반 상태와 같은 "총 자산" 제목을 써야 한다 — 개명이 이번 Task의 핵심
    // 변경인데, 두 분기 중 하나만 고정돼 있으면 이 h1이 회귀해도 기존 테스트로는 못 잡는다.
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('총 자산');
    expect(screen.getByText('등록된 자산이 없습니다.')).toBeTruthy();
    expect(screen.getByTestId('portfolio-add-asset-button')).toBeTruthy();
  });
});
