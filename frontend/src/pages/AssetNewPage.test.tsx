import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { describe, expect, it } from 'vitest';
import AssetNewPage from './AssetNewPage';
import { VALIDATION_MESSAGES } from '../lib/messages';

// PortfolioPage 자체는 이 테스트의 관심사가 아니다 — AssetNewPage가 navigate로 넘기는
// flash state가 실제로 전달되는지만 확인하면 되므로, /portfolio 자리에는 location.state를
// 그대로 노출하는 더미 컴포넌트를 둔다(LoginPage.test.tsx의 더미 라우트 패턴 응용).
function PortfolioFlashProbe() {
  const location = useLocation();
  const state = location.state as { flash?: { tone: string; message: string } } | null;
  return <div data-testid="portfolio-page">{state?.flash?.message}</div>;
}

function renderAssetNewPage() {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/assets/new' }]}>
      <Routes>
        <Route path="/assets/new" element={<AssetNewPage />} />
        <Route path="/portfolio" element={<PortfolioFlashProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

function fillCommonFields() {
  fireEvent.change(screen.getByTestId('asset-new-ticker'), { target: { value: 'AAPL' } });
  fireEvent.change(screen.getByTestId('asset-new-name'), { target: { value: 'Apple Inc.' } });
  fireEvent.click(screen.getByTestId('asset-new-currency-usd'));
  fireEvent.change(screen.getByTestId('asset-new-quantity'), { target: { value: '10' } });
}

describe('AssetNewPage', () => {
  it('필수 입력이 비어 있는 상태로 제출하면 각 필드에 필수 입력 에러가 뜬다', () => {
    renderAssetNewPage();

    fireEvent.click(screen.getByTestId('asset-new-submit'));

    // 통화는 KRW/USD 선택 UI라 항상 기본값(KRW)이 채워져 있다 — 비워질 수 없다.
    // STOCK(기본 선택)에서 ticker/name/quantity/avgPrice 4개 필드가 모두 비어 있다.
    expect(screen.getAllByText(VALIDATION_MESSAGES.REQUIRED)).toHaveLength(4);
  });

  it('티커에 공백이 섞여 있으면 공백 에러를 보여준다', () => {
    renderAssetNewPage();

    fireEvent.change(screen.getByTestId('asset-new-ticker'), { target: { value: 'AB C' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    expect(screen.getByText(VALIDATION_MESSAGES.TICKER_WHITESPACE)).toBeTruthy();
  });

  it('CASH로 전환하면 평단가 입력란이 사라지고, 나머지 필드만 채워도 등록에 성공한다', async () => {
    renderAssetNewPage();

    fireEvent.click(screen.getByTestId('asset-new-type-cash'));
    expect(screen.queryByTestId('asset-new-avg-price')).toBeNull();

    fillCommonFields();
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    expect(screen.queryByText(VALIDATION_MESSAGES.PRICE_FORBIDDEN_FOR_CASH)).toBeNull();
    await waitFor(() => expect(screen.getByTestId('portfolio-page')).toBeTruthy());
    expect(screen.getByTestId('portfolio-page').textContent).toBe('자산이 등록되었습니다.');
  });

  it('STOCK에서 평단가를 입력한 뒤 CASH로 전환해도 평단가 값이 리셋되어 정상 제출된다', async () => {
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
    renderAssetNewPage();

    fillCommonFields();
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '0' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    expect(screen.getByText(VALIDATION_MESSAGES.PRICE_NOT_POSITIVE)).toBeTruthy();
  });

  it('평단가 에러가 뜬 채로 CASH로 전환했다가 STOCK으로 복귀하면 이전 평단가 에러는 다시 뜨지 않는다', () => {
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
    renderAssetNewPage();

    fillCommonFields();
    fireEvent.change(screen.getByTestId('asset-new-avg-price'), { target: { value: '150.25' } });
    fireEvent.click(screen.getByTestId('asset-new-submit'));

    await waitFor(() => expect(screen.getByTestId('portfolio-page')).toBeTruthy());
    expect(screen.getByTestId('portfolio-page').textContent).toBe('자산이 등록되었습니다.');
  });
});
