import { render, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import CandlestickChart from './CandlestickChart';
import type { CandleBarResponse } from '../api/types';

// lightweight-charts는 캔버스 기반 렌더러라 happy-dom에서 실제 그리기 결과를 검증할 수 없다 —
// createChart/addSeries가 반환하는 API 호출(setData/createPriceLine/removePriceLine)만
// 스텁으로 기록해 "이 컴포넌트가 라이브러리를 올바르게 호출하는지"를 검증한다.
// vi.mock의 factory는 다른 import보다 앞으로 호이스팅되므로, factory 안에서 참조하는 값은
// vi.hoisted로 함께 끌어올려야 한다(그냥 top-level const로 두면 "Cannot access before
// initialization"이 난다 — 전체 스위트에서 실측 확인됨, 단일 파일 실행에서는 우연히 통과했었다).
const mocks = vi.hoisted(() => {
  const setDataMock = vi.fn();
  const createPriceLineMock = vi.fn((..._args: unknown[]) => ({ line: true }));
  const removePriceLineMock = vi.fn();
  const removeChartMock = vi.fn();
  const addSeriesMock = vi.fn((..._args: unknown[]) => ({
    setData: setDataMock,
    createPriceLine: createPriceLineMock,
    removePriceLine: removePriceLineMock,
  }));
  const createChartMock = vi.fn((..._args: unknown[]) => ({
    addSeries: addSeriesMock,
    remove: removeChartMock,
  }));
  return {
    setDataMock,
    createPriceLineMock,
    removePriceLineMock,
    removeChartMock,
    addSeriesMock,
    createChartMock,
  };
});

vi.mock('lightweight-charts', () => ({
  createChart: mocks.createChartMock,
  CandlestickSeries: 'CandlestickSeries',
  LineStyle: { Solid: 0, Dotted: 1, Dashed: 2, LargeDashed: 3, SparseDotted: 4 },
}));

const bars: CandleBarResponse[] = [
  { bucketStart: '2026-09-01T00:00:00Z', open: '100', high: '110', low: '90', close: '105' },
  { bucketStart: '2026-09-02T00:00:00Z', open: '105', high: '115', low: '95', close: '108' },
];

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('CandlestickChart', () => {
  it('마운트 시 차트와 캔들 시리즈를 생성하고, bars를 setData로 전달한다', () => {
    render(<CandlestickChart bars={bars} testId="chart" />);

    expect(mocks.createChartMock).toHaveBeenCalledTimes(1);
    expect(mocks.addSeriesMock).toHaveBeenCalledTimes(1);
    expect(mocks.setDataMock).toHaveBeenCalledTimes(1);

    const [passedBars] = mocks.setDataMock.mock.calls[0] as [
      Array<{ time: number; close: number }>,
    ];
    expect(passedBars).toHaveLength(2);
    // 금액 문자열이 숫자로 변환되어 전달된다(차트 좌표 전용 예외 — frontend/CLAUDE.md 규칙 대상 아님).
    expect(passedBars[0].close).toBe(105);
  });

  it('priceLines를 series.createPriceLine으로 그린다', () => {
    render(
      <CandlestickChart
        bars={bars}
        priceLines={[{ id: 'current', price: '100', color: '#0e1c31', title: '현재 100' }]}
        testId="chart"
      />,
    );

    expect(mocks.createPriceLineMock).toHaveBeenCalledTimes(1);
    expect(mocks.createPriceLineMock.mock.calls[0][0]).toMatchObject({
      price: 100,
      color: '#0e1c31',
      title: '현재 100',
    });
  });

  it('priceLines가 바뀌면 이전 선을 지우고 새로 긋는다', () => {
    const { rerender } = render(
      <CandlestickChart
        bars={bars}
        priceLines={[{ id: 'current', price: '100', color: '#0e1c31', title: '현재 100' }]}
        testId="chart"
      />,
    );
    expect(mocks.createPriceLineMock).toHaveBeenCalledTimes(1);

    rerender(
      <CandlestickChart
        bars={bars}
        priceLines={[
          { id: 'current', price: '100', color: '#0e1c31', title: '현재 100' },
          { id: 'expected', price: '120', color: '#ce2e26', title: '예상 120' },
        ]}
        testId="chart"
      />,
    );

    expect(mocks.removePriceLineMock).toHaveBeenCalledTimes(1);
    expect(mocks.createPriceLineMock).toHaveBeenCalledTimes(3); // 최초 1회 + 재긋기 2회
  });

  it('언마운트 시 chart.remove()로 정리한다', () => {
    const { unmount } = render(<CandlestickChart bars={bars} testId="chart" />);
    unmount();
    expect(mocks.removeChartMock).toHaveBeenCalledTimes(1);
  });

  // 회귀 테스트: 언마운트 시 차트 생성 effect(cleanup: chart.remove())와 평단선 effect
  // (cleanup: series.removePriceLine)가 선언 순서대로 정리되는데, 실제 lightweight-charts는
  // chart.remove() 이후 이미 폐기된 series에 removePriceLine을 호출하면 "Object is disposed"를
  // 던진다. 이 스텁은 그 순서 의존적인 폐기 동작을 흉내 내어 회귀를 잡는다(ui-ux-designer가
  // Playwright 실측으로 발견 — interval 전환으로 컴포넌트가 unmount→remount될 때 재현).
  it('언마운트 시 차트가 먼저 폐기돼도 평단선 cleanup에서 에러가 나지 않는다(disposed 재현)', () => {
    let disposed = false;
    mocks.removeChartMock.mockImplementationOnce(() => {
      disposed = true;
    });
    mocks.removePriceLineMock.mockImplementationOnce(() => {
      if (disposed) throw new Error('Object is disposed');
    });

    const { unmount } = render(
      <CandlestickChart
        bars={bars}
        priceLines={[{ id: 'current', price: '100', color: '#0e1c31', title: '현재 100' }]}
        testId="chart"
      />,
    );

    expect(() => unmount()).not.toThrow();
    // 수정 후에는 평단선 cleanup이 폐기된 series에 접근조차 하지 않는다.
    expect(mocks.removePriceLineMock).not.toHaveBeenCalled();
  });
});
