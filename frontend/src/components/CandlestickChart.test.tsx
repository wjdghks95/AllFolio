import { StrictMode } from 'react';
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
  const subscribeVisibleLogicalRangeChangeMock = vi.fn();
  const unsubscribeVisibleLogicalRangeChangeMock = vi.fn();
  const fitContentMock = vi.fn();
  const setVisibleLogicalRangeMock = vi.fn();
  const timeScaleMock = vi.fn(() => ({
    subscribeVisibleLogicalRangeChange: subscribeVisibleLogicalRangeChangeMock,
    unsubscribeVisibleLogicalRangeChange: unsubscribeVisibleLogicalRangeChangeMock,
    fitContent: fitContentMock,
    setVisibleLogicalRange: setVisibleLogicalRangeMock,
  }));
  const applyOptionsMock = vi.fn();
  const createChartMock = vi.fn((..._args: unknown[]) => ({
    addSeries: addSeriesMock,
    remove: removeChartMock,
    timeScale: timeScaleMock,
    applyOptions: applyOptionsMock,
  }));
  return {
    setDataMock,
    createPriceLineMock,
    removePriceLineMock,
    removeChartMock,
    addSeriesMock,
    createChartMock,
    timeScaleMock,
    subscribeVisibleLogicalRangeChangeMock,
    unsubscribeVisibleLogicalRangeChangeMock,
    fitContentMock,
    setVisibleLogicalRangeMock,
    applyOptionsMock,
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

  // 로드된 캔들 범위 밖(빈 캔버스)으로 줌아웃·스크롤되지 않도록, 그리고 캔들이 적을 때 비정상적으로
  // 뚱뚱해지지 않도록 timeScale에 넘기는 옵션들을 회귀 테스트로 고정한다.
  it('timeScale에 fixLeftEdge/fixRightEdge/maxBarSpacing을 전달한다', () => {
    render(<CandlestickChart bars={bars} testId="chart" />);

    const [, options] = mocks.createChartMock.mock.calls[0] as [
      unknown,
      { timeScale: { fixLeftEdge: boolean; fixRightEdge: boolean; maxBarSpacing: number } },
    ];
    expect(options.timeScale).toMatchObject({
      fixLeftEdge: true,
      fixRightEdge: true,
      maxBarSpacing: 48,
    });
  });

  it('마운트 후 처음 bars가 채워질 때 fitContent()를 1번 호출한다', () => {
    render(<CandlestickChart bars={bars} testId="chart" />);

    expect(mocks.fitContentMock).toHaveBeenCalledTimes(1);
  });

  it('과거 구간 병합 등으로 bars가 다시 바뀌어도 fitContent()를 추가로 호출하지 않는다', () => {
    const { rerender } = render(<CandlestickChart bars={bars} testId="chart" />);
    expect(mocks.fitContentMock).toHaveBeenCalledTimes(1);

    const mergedBars: CandleBarResponse[] = [
      { bucketStart: '2026-08-31T00:00:00Z', open: '90', high: '100', low: '85', close: '95' },
      ...bars,
    ];
    rerender(<CandlestickChart bars={mergedBars} testId="chart" />);

    expect(mocks.fitContentMock).toHaveBeenCalledTimes(1);
  });

  // 캔들이 적어 정상 폭(MAX_BAR_SPACING=48px)으로 그려도 컨테이너를 못 채우는 경우: 왼쪽 정렬 +
  // 오른쪽 여백 배치를 직접 지정해야 하므로 fitContent() 대신 fixRightEdge를 끄고
  // setVisibleLogicalRange를 호출해야 한다(§ 위 fixRightEdge 주석 근거). happy-dom은 기본
  // clientWidth가 0이라 이 분기를 타려면 clientWidth를 직접 스텁해야 한다.
  describe('캔들 개수가 적어 컨테이너를 못 채우는 경우(예: 년봉)', () => {
    const fewBars: CandleBarResponse[] = [
      { bucketStart: '2020-01-01T00:00:00Z', open: '100', high: '110', low: '90', close: '105' },
      { bucketStart: '2021-01-01T00:00:00Z', open: '105', high: '115', low: '95', close: '108' },
    ];

    it('fixRightEdge를 끄고 왼쪽 정렬 논리 범위(setVisibleLogicalRange)를 지정한다', () => {
      const widthSpy = vi
        .spyOn(HTMLElement.prototype, 'clientWidth', 'get')
        .mockReturnValue(240); // 240 / 48 = 5칸 — fewBars(2개)로는 다 못 채움

      render(<CandlestickChart bars={fewBars} testId="chart" />);

      expect(mocks.applyOptionsMock).toHaveBeenCalledWith({
        timeScale: { fixRightEdge: false },
      });
      expect(mocks.setVisibleLogicalRangeMock).toHaveBeenCalledWith({ from: -0.5, to: 4.5 });
      expect(mocks.fitContentMock).not.toHaveBeenCalled();

      widthSpy.mockRestore();
    });

    it('컨테이너를 채우기 충분한 캔들 개수면 기존처럼 fitContent()를 쓰고 fixRightEdge를 끄지 않는다', () => {
      const widthSpy = vi
        .spyOn(HTMLElement.prototype, 'clientWidth', 'get')
        .mockReturnValue(240); // 240 / 48 = 5칸 — 아래 manyBars(30개)면 넘치도록 채움
      const manyBars: CandleBarResponse[] = Array.from({ length: 30 }, (_, i) => ({
        bucketStart: `2026-01-${String((i % 28) + 1).padStart(2, '0')}T00:00:00Z`,
        open: '100',
        high: '110',
        low: '90',
        close: '105',
      }));

      render(<CandlestickChart bars={manyBars} testId="chart" />);

      expect(mocks.fitContentMock).toHaveBeenCalledTimes(1);
      expect(mocks.setVisibleLogicalRangeMock).not.toHaveBeenCalled();
      expect(mocks.applyOptionsMock).not.toHaveBeenCalledWith({
        timeScale: { fixRightEdge: false },
      });

      widthSpy.mockRestore();
    });

    // fixRightEdge를 켤 수 없는 이 분기에서는 라이브러리의 느슨한 기본 상한("화면에 최소 2개
    // 캔들만 남으면 됨")까지 사용자가 마우스 휠로 계속 축소해 여백을 더 벌릴 수 있다 — 최초에
    // 고정해둔 오른쪽 경계(to)를 handleVisibleLogicalRangeChange가 직접 감시해 되돌린다.
    describe('배치 후 오른쪽 여백이 고정 경계보다 더 벌어지려 하면', () => {
      function renderFewBarsAndGetHandler() {
        const widthSpy = vi
          .spyOn(HTMLElement.prototype, 'clientWidth', 'get')
          .mockReturnValue(240); // 240 / 48 = 5칸 → 고정 경계 to = -0.5 + 5 = 4.5

        render(<CandlestickChart bars={fewBars} testId="chart" />);
        // 최초 배치 시 호출된 setVisibleLogicalRange는 이번 검증 대상이 아니므로 비운다.
        mocks.setVisibleLogicalRangeMock.mockClear();

        const [handler] = mocks.subscribeVisibleLogicalRangeChangeMock.mock.calls[0] as [
          (range: { from: number; to: number } | null) => void,
        ];
        return { handler, widthSpy };
      }

      it('고정 경계(4.5)를 넘는 range가 오면 고정 범위로 되돌린다', () => {
        const { handler, widthSpy } = renderFewBarsAndGetHandler();

        handler({ from: 0, to: 6 }); // 과도한 축소를 흉내 — 고정 경계보다 더 벌어짐

        expect(mocks.setVisibleLogicalRangeMock).toHaveBeenCalledWith({ from: -0.5, to: 4.5 });

        widthSpy.mockRestore();
      });

      it('고정 경계 이내면 되돌리지 않는다', () => {
        const { handler, widthSpy } = renderFewBarsAndGetHandler();

        handler({ from: 0, to: 4 }); // 경계(4.5) 이내

        expect(mocks.setVisibleLogicalRangeMock).not.toHaveBeenCalled();

        widthSpy.mockRestore();
      });
    });

    it('컨테이너를 채우기 충분한(dense) 차트는 고정 경계가 없어 이 클램프가 개입하지 않는다', () => {
      const widthSpy = vi
        .spyOn(HTMLElement.prototype, 'clientWidth', 'get')
        .mockReturnValue(240);
      const manyBars: CandleBarResponse[] = Array.from({ length: 30 }, (_, i) => ({
        bucketStart: `2026-01-${String((i % 28) + 1).padStart(2, '0')}T00:00:00Z`,
        open: '100',
        high: '110',
        low: '90',
        close: '105',
      }));

      render(<CandlestickChart bars={manyBars} testId="chart" />);
      mocks.setVisibleLogicalRangeMock.mockClear();

      const [handler] = mocks.subscribeVisibleLogicalRangeChangeMock.mock.calls[0] as [
        (range: { from: number; to: number } | null) => void,
      ];
      handler({ from: 0, to: 1000 }); // 아주 큰 to여도 dense 차트는 이미 fixRightEdge가 막는다

      expect(mocks.setVisibleLogicalRangeMock).not.toHaveBeenCalled();

      widthSpy.mockRestore();
    });
  });

  // 회귀 테스트: hasFitRef가 컴포넌트 레벨에서 한 번만 true가 되도록 짜면, React.StrictMode의
  // 이중 이펙트 실행(mount→cleanup→mount, 같은 컴포넌트 인스턴스 안에서 useRef 값은 유지됨)에서
  // 첫 번째(임시) 마운트가 fit을 이미 소모해버려, 실제로 화면에 남는 두 번째 마운트는 fit을
  // 건너뛴다 — 캔들 개수가 적은 interval에서 캔들이 오른쪽에 몰리고 왼쪽이 비어 보이는 버그로
  // 실측 재현됐다. hasFitRef는 차트 생성 이펙트가 실행될 때마다(=차트 인스턴스가 새로 만들어질
  // 때마다) 리셋돼야 하고, 이 테스트는 StrictMode를 직접 씌워 그 리셋을 검증한다.
  it('StrictMode 이중 마운트에서도 최종적으로 살아남은 차트에 fitContent가 호출된다', () => {
    render(
      <StrictMode>
        <CandlestickChart bars={bars} testId="chart" />
      </StrictMode>,
    );

    // 임시 마운트 + 실제 마운트, 총 2개 차트 인스턴스가 만들어지고 각각 자신의 fit을 소비한다 —
    // 중요한 건 "0번 호출"이 되지 않는 것(=실제로 남는 차트가 fit을 건너뛰지 않는 것)이다.
    expect(mocks.createChartMock).toHaveBeenCalledTimes(2);
    expect(mocks.fitContentMock).toHaveBeenCalledTimes(2);
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

  // 평단선을 세로 스케일(autoscaleInfoProvider)에 항상 포함시키는 로직 — addSeries에 넘긴
  // 옵션에서 함수를 꺼내 직접 호출해 검증한다(캔버스 렌더링 자체는 검증 대상이 아니다).
  // "주식 차트는 괴리가 커도 평단선이 항상 보인다"는 사용자 확인 기준에 맞춰, 평단가와 캔들
  // 범위가 아무리 멀어도 세로 범위 계산에서 제외하지 않는다(사용자가 명시적으로 선택한 동작).
  describe('autoscaleInfoProvider(평단선을 세로 스케일에 항상 포함)', () => {
    function getAutoscaleInfoProvider() {
      const [, options] = mocks.addSeriesMock.mock.calls[0] as [
        unknown,
        {
          autoscaleInfoProvider: (
            original: () => { priceRange: { minValue: number; maxValue: number } } | null,
          ) => { priceRange: { minValue: number; maxValue: number } } | null;
        },
      ];
      return options.autoscaleInfoProvider;
    }

    it('평단가가 캔들 변동폭과 가까우면 세로 범위에 포함한다', () => {
      render(
        <CandlestickChart
          bars={bars}
          priceLines={[{ id: 'current', price: '108', color: '#0e1c31', title: '현재 108' }]}
          testId="chart"
        />,
      );
      const autoscaleInfoProvider = getAutoscaleInfoProvider();
      const original = () => ({ priceRange: { minValue: 95, maxValue: 115 } });

      const result = autoscaleInfoProvider(original);

      // 평단가(108)는 이미 캔들 범위(95~115) 안이라 그대로 포함된다.
      expect(result?.priceRange).toEqual({ minValue: 95, maxValue: 115 });
    });

    it('평단가가 캔들 변동폭과 아무리 멀어도 세로 범위에 항상 포함한다(비트코인 1분봉 실측 재현)', () => {
      render(
        <CandlestickChart
          bars={bars}
          priceLines={[{ id: 'current', price: '80000000', color: '#0e1c31', title: '평단' }]}
          testId="chart"
        />,
      );
      const autoscaleInfoProvider = getAutoscaleInfoProvider();
      // 캔들 자체 변동폭은 좁은데(115,000,000~115,500,000) 평단가(80,000,000)는 그보다 훨씬
      // 멀리 떨어져 있다 — 그래도 평단선이 항상 보여야 한다는 요구사항이라 minValue/maxValue
      // 계산에 그대로 포함돼야 한다.
      const original = () => ({ priceRange: { minValue: 115_000_000, maxValue: 115_500_000 } });

      const result = autoscaleInfoProvider(original);

      expect(result?.priceRange).toEqual({ minValue: 80_000_000, maxValue: 115_500_000 });
    });

    it('평단선이 없으면 원래 범위를 그대로 반환한다', () => {
      render(<CandlestickChart bars={bars} testId="chart" />);
      const autoscaleInfoProvider = getAutoscaleInfoProvider();
      const original = () => ({ priceRange: { minValue: 95, maxValue: 115 } });

      const result = autoscaleInfoProvider(original);

      expect(result?.priceRange).toEqual({ minValue: 95, maxValue: 115 });
    });
  });

  it('언마운트 시 chart.remove()로 정리한다', () => {
    const { unmount } = render(<CandlestickChart bars={bars} testId="chart" />);
    unmount();
    expect(mocks.removeChartMock).toHaveBeenCalledTimes(1);
  });

  it('언마운트 시 subscribeVisibleLogicalRangeChange 핸들러를 해제한다', () => {
    const { unmount } = render(<CandlestickChart bars={bars} testId="chart" />);
    expect(mocks.subscribeVisibleLogicalRangeChangeMock).toHaveBeenCalledTimes(1);
    const [handler] = mocks.subscribeVisibleLogicalRangeChangeMock.mock.calls[0] as [
      (range: unknown) => void,
    ];
    unmount();
    expect(mocks.unsubscribeVisibleLogicalRangeChangeMock).toHaveBeenCalledWith(handler);
  });

  // 가로 스크롤/줌아웃으로 왼쪽(과거) 끝에 가까워지면 onNeedMoreHistory가 자동으로 호출된다.
  // lightweight-charts는 실제 캔버스를 그려 스크롤을 흉내 낼 수 없으므로, subscribe 호출 시
  // 등록된 핸들러를 직접 호출해 "왼쪽 끝 근접" 이벤트를 재현한다(위 mocks 스텁 패턴).
  describe('가로 스크롤/줌아웃으로 과거 끝에 가까워지면', () => {
    function fireVisibleLogicalRangeChange(range: { from: number; to: number } | null) {
      const [handler] = mocks.subscribeVisibleLogicalRangeChangeMock.mock.calls[0] as [
        (range: { from: number; to: number } | null) => void,
      ];
      handler(range);
    }

    it('hasMoreHistory가 true이고 로딩 중이 아니면 onNeedMoreHistory를 호출한다', () => {
      const onNeedMoreHistory = vi.fn();
      render(
        <CandlestickChart
          bars={bars}
          hasMoreHistory
          loadingMoreHistory={false}
          onNeedMoreHistory={onNeedMoreHistory}
          testId="chart"
        />,
      );

      fireVisibleLogicalRangeChange({ from: 3, to: 20 });

      expect(onNeedMoreHistory).toHaveBeenCalledTimes(1);
    });

    it('range가 null이면 호출하지 않는다', () => {
      const onNeedMoreHistory = vi.fn();
      render(
        <CandlestickChart
          bars={bars}
          hasMoreHistory
          loadingMoreHistory={false}
          onNeedMoreHistory={onNeedMoreHistory}
          testId="chart"
        />,
      );

      fireVisibleLogicalRangeChange(null);

      expect(onNeedMoreHistory).not.toHaveBeenCalled();
    });

    it('from이 임계값보다 크면(과거 끝과 아직 멀면) 호출하지 않는다', () => {
      const onNeedMoreHistory = vi.fn();
      render(
        <CandlestickChart
          bars={bars}
          hasMoreHistory
          loadingMoreHistory={false}
          onNeedMoreHistory={onNeedMoreHistory}
          testId="chart"
        />,
      );

      fireVisibleLogicalRangeChange({ from: 50, to: 70 });

      expect(onNeedMoreHistory).not.toHaveBeenCalled();
    });

    it('hasMoreHistory가 false면 호출하지 않는다', () => {
      const onNeedMoreHistory = vi.fn();
      render(
        <CandlestickChart
          bars={bars}
          hasMoreHistory={false}
          loadingMoreHistory={false}
          onNeedMoreHistory={onNeedMoreHistory}
          testId="chart"
        />,
      );

      fireVisibleLogicalRangeChange({ from: 3, to: 20 });

      expect(onNeedMoreHistory).not.toHaveBeenCalled();
    });

    it('이미 로딩 중이면 중복 호출하지 않는다', () => {
      const onNeedMoreHistory = vi.fn();
      render(
        <CandlestickChart
          bars={bars}
          hasMoreHistory
          loadingMoreHistory
          onNeedMoreHistory={onNeedMoreHistory}
          testId="chart"
        />,
      );

      fireVisibleLogicalRangeChange({ from: 3, to: 20 });

      expect(onNeedMoreHistory).not.toHaveBeenCalled();
    });

    it('prop이 리렌더로 바뀌어도 재구독 없이 최신 값을 읽는다', () => {
      const onNeedMoreHistory = vi.fn();
      const { rerender } = render(
        <CandlestickChart
          bars={bars}
          hasMoreHistory={false}
          loadingMoreHistory={false}
          onNeedMoreHistory={onNeedMoreHistory}
          testId="chart"
        />,
      );

      fireVisibleLogicalRangeChange({ from: 3, to: 20 });
      expect(onNeedMoreHistory).not.toHaveBeenCalled();

      rerender(
        <CandlestickChart
          bars={bars}
          hasMoreHistory
          loadingMoreHistory={false}
          onNeedMoreHistory={onNeedMoreHistory}
          testId="chart"
        />,
      );
      // 차트 생성 effect가 재구독되지 않았음을 함께 확인한다.
      expect(mocks.subscribeVisibleLogicalRangeChangeMock).toHaveBeenCalledTimes(1);

      fireVisibleLogicalRangeChange({ from: 3, to: 20 });
      expect(onNeedMoreHistory).toHaveBeenCalledTimes(1);
    });
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
