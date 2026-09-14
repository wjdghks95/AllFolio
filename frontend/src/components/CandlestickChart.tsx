// 구조·동작: senior-frontend / 색상·크기 등 세부 시각 표현은 ui-ux-designer 소관(다음 단계에서 다듬음).
//
// 라이브러리 선택(Task 028 프론트 7번째 하위 태스크): TradingView `lightweight-charts`.
// 캔들 렌더링 + 평단가 수평선을 `createPriceLine` API로 한 번에 지원해 이 화면의 요구사항
// (캔들 차트 위에 현재/예상 평단선을 겹쳐 그리기)과 정확히 맞아떨어진다 — 기존
// AssetDetailPage.tsx가 CSS `absolute` div로 흉내 내던 평단선(507~561행)을 대체한다.
import { useEffect, useRef } from 'react';
import {
  createChart,
  CandlestickSeries,
  LineStyle,
  type AutoscaleInfo,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts';
import type { CandleBarResponse, Money } from '../api/types';
import { CHART_LINE_COLOR, CHART_TOKEN } from '../lib/chartColors';

// 백엔드(PrecisionScale)가 이미 스케일·반올림을 끝낸 문자열을 내려준다 — 여기서의 Number() 변환은
// 값 계산이 아니라 차트 좌표(픽셀 위치) 변환 전용이라 frontend/CLAUDE.md의 "금액 계산은 Dec로만"
// 규칙 대상이 아니다(이번 태스크에서 확정된 결정). 화면에 텍스트로 보여주는 숫자(현재가 배지 등)는
// 호출부가 여전히 lib/money.ts(formatAmount 등)를 거쳐야 한다 — 이 컴포넌트는 텍스트를 그리지 않는다.
function toChartNumber(value: Money): number {
  return Number(value);
}

// 가격축 눈금 라벨 포맷. 라이브러리 기본값은 소수 2자리 고정 + 천 단위 구분 없음이라
// `94000000.00`(11자)이 되어, 375px에서 가격축 하나가 플롯 폭의 절반을 가져갔다(실측).
// 눈금은 값을 정확히 읽는 자리가 아니라 캔들의 높이를 가늠하는 자리이므로 자리수를 줄인다 —
// 정확한 값은 상세 정보 카드(§6-4)와 평단선 뱃지가 말한다.
// 여기 들어오는 price는 API의 금액 문자열이 아니라 라이브러리가 만들어 낸 눈금 좌표(number)라
// lib/money.ts(문자열 전용 포맷터)를 태울 수 없다 — toChartNumber와 같은 성격의 예외다.
const TICK_FORMATTERS = {
  // 원화 만 원대 이상: 소수점은 눈금에서 의미가 없다.
  coarse: new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 }),
  // 달러 주식·환산 전 단가대(1 이상): 센트 단위까지.
  medium: new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }),
  // 1 미만(알트코인 등): 유효 자리가 소수점 아래에만 있어 자르면 눈금이 전부 같은 값이 된다.
  fine: new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 8 }),
} as const;

function formatTickPrice(price: number): string {
  const magnitude = Math.abs(price);
  if (magnitude >= 1000) return TICK_FORMATTERS.coarse.format(price);
  if (magnitude >= 1) return TICK_FORMATTERS.medium.format(price);
  return TICK_FORMATTERS.fine.format(price);
}

// COIN의 bucketStart는 Instant 문자열("2026-09-11T00:00:00Z"), STOCK은 LocalDate 문자열
// ("2026-09-11")이다. 날짜만 있는 ISO 문자열도 Date가 UTC 자정으로 해석하므로 변환 함수 하나로
// 두 경우를 함께 처리할 수 있다.
function toChartTime(bucketStart: string): UTCTimestamp {
  return Math.floor(new Date(bucketStart).getTime() / 1000) as UTCTimestamp;
}

// id는 안정적인 식별 용도(테스트·디버깅)다 — title은 호출부가 만든, 사람이 읽는 라벨 문자열이라
// 화면 카피가 바뀌어도 id는 바뀌지 않는다.
export interface PriceLineSpec {
  id: 'current' | 'expected';
  price: Money;
  color: string;
  title: string;
}

export interface CandlestickChartProps {
  // 오름차순(과거→현재) 정렬은 호출부 책임이다 — 백엔드 응답은 내림차순이므로 넘기기 전에 뒤집어야 한다.
  bars: CandleBarResponse[];
  priceLines?: PriceLineSpec[];
  testId?: string;
}

export default function CandlestickChart({ bars, priceLines = [], testId }: CandlestickChartProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  // 아래 autoscaleInfoProvider가 읽는 현재 평단선 값들. 라이브러리는 가격선을 세로 스케일
  // 계산에 넣지 않아서, 평단가가 화면에 보이는 캔들 범위 밖이면 이 서비스의 시그니처(§5)가
  // 통째로 잘려 나간다 — 375px 삼성전자 화면에서 실측(캔들 62,900~67,200 / 평단 60,000).
  const priceLineValuesRef = useRef<number[]>([]);
  // 차트(및 series)가 이미 폐기됐는지 여부. 언마운트 시 아래 세 useEffect의 cleanup이 선언 순서
  // (차트 생성 → 데이터 → 평단선) 그대로 실행되는데, 차트 cleanup의 chart.remove()가 series까지
  // 통째로 폐기한 뒤 평단선 cleanup이 그 폐기된 series에 removePriceLine을 호출하면
  // lightweight-charts가 "Object is disposed"를 던진다(interval 전환으로 이 컴포넌트가
  // unmount→remount될 때 실측 재현). 차트가 이미 사라졌으면 그 위 평단선도 함께 사라졌으므로
  // 정리할 것이 없다 — 이 플래그로 그 경우만 건너뛴다.
  const disposedRef = useRef(false);

  // 차트/시리즈 생성은 마운트 시 1회 — container가 리마운트되지 않는 한 재생성하지 않는다.
  useEffect(() => {
    if (!containerRef.current) return;
    const chart: IChartApi = createChart(containerRef.current, {
      autoSize: true,
      layout: {
        background: { color: CHART_TOKEN.surface },
        // 축 글자는 읽을 값이 아니라 값을 읽기 위한 눈금이다 — 본문 잉크가 아니라 보조 잉크로
        // 한 단 내리고(§2-1), 숫자라서 등폭 11px(§3 유틸리티 역할)로 찍는다.
        textColor: CHART_TOKEN.inkSoft,
        fontFamily: CHART_TOKEN.fontMono,
        fontSize: 11,
      },
      // 격자는 잉크 7% — 배경 격자지(§5)와 같은 농도라 캔들이 "모눈종이 위"에 놓인다.
      grid: {
        vertLines: { color: CHART_TOKEN.plotGrid },
        horzLines: { color: CHART_TOKEN.plotGrid },
      },
      // 축 경계선은 카드 구분선과 같은 rule 1px. 격자(7%)보다 한 단 진해 축이 격자에 묻히지 않는다.
      rightPriceScale: { borderColor: CHART_TOKEN.rule },
      // 코인 1분봉에서 눈금이 날짜만 반복되지 않도록 시각 표시를 허용한다(초 단위는 쓰지 않는다).
      // 일·주·월·년봉에서는 라이브러리가 간격을 보고 날짜 눈금을 그대로 쓴다.
      timeScale: { borderColor: CHART_TOKEN.rule, timeVisible: true, secondsVisible: false },
      localization: { priceFormatter: formatTickPrice },
      // 크로스헤어는 손익 축(빨강/파랑)을 건드리지 않는 무채로 둔다 — 커서를 따라다니는 선이
      // 유채색이면 "유채색은 정보일 때만"(§2-3)이 깨지고 캔들 색과 같은 층위로 읽힌다.
      crosshair: {
        vertLine: { color: CHART_TOKEN.inkSoft, labelBackgroundColor: CHART_TOKEN.ink },
        horzLine: { color: CHART_TOKEN.inkSoft, labelBackgroundColor: CHART_TOKEN.ink },
      },
    });
    // 캔들 색은 한국 증권 관례 그대로 상승 빨강 / 하락 파랑(§2-2). 한 캔들의 빨강/파랑은
    // 색 하나에 기대지만, 이 차트가 답하는 값(가격이 어디에 있고 어떻게 움직였나)은 색이 아니라
    // **좌표**가 말한다 — 색을 못 읽어도 가격의 흐름은 그대로 읽힌다. 부호·방향 표식이 필요한
    // 신호(평단 대비 손익)는 캔버스가 아니라 상세 정보 카드의 텍스트가 맡는다(§2-2).
    const series = chart.addSeries(CandlestickSeries, {
      upColor: CHART_LINE_COLOR.gain,
      downColor: CHART_LINE_COLOR.loss,
      borderVisible: false,
      wickUpColor: CHART_LINE_COLOR.gain,
      wickDownColor: CHART_LINE_COLOR.loss,
      // 라이브러리가 기본으로 그리는 "마지막 종가" 점선을 끈다. 이 화면에서 가로 점선은
      // 평단선 한 종류뿐이라는 약속(§5)을 깨고, 실제로 375px에서는 평단선이 잘린 채 이 점선만
      // 남아 그것이 평단선처럼 읽혔다(실측). 마지막 종가는 가격축 뱃지로만 남긴다.
      priceLineVisible: false,
      lastValueVisible: true,
      // 평단선을 세로 스케일 계산에 함께 넣는다 — 시그니처가 범위 밖이라는 이유로 사라지면
      // 이 카드는 "가격 흐름"만 남고 "내 평단은 어디인가"에 답하지 못한다(§1·§5).
      autoscaleInfoProvider: (original: () => AutoscaleInfo | null): AutoscaleInfo | null => {
        const base = original();
        const values = priceLineValuesRef.current.filter((value) => Number.isFinite(value));
        if (values.length === 0) return base;
        const candidates = [...values];
        if (base?.priceRange) {
          candidates.push(base.priceRange.minValue, base.priceRange.maxValue);
        }
        return {
          ...base,
          priceRange: { minValue: Math.min(...candidates), maxValue: Math.max(...candidates) },
        };
      },
    });
    seriesRef.current = series;
    disposedRef.current = false;
    return () => {
      disposedRef.current = true;
      chart.remove();
      seriesRef.current = null;
    };
  }, []);

  // 데이터 갱신(interval 전환·과거 페이징 병합)마다 setData만 다시 호출한다.
  useEffect(() => {
    const series = seriesRef.current;
    if (!series) return;
    series.setData(
      bars.map((bar) => ({
        time: toChartTime(bar.bucketStart),
        open: toChartNumber(bar.open),
        high: toChartNumber(bar.high),
        low: toChartNumber(bar.low),
        close: toChartNumber(bar.close),
      })),
    );
  }, [bars]);

  // 평단가 수평선(현재/예상) — priceLines가 바뀌면 기존 선을 지우고 새로 긋는다.
  useEffect(() => {
    const series = seriesRef.current;
    if (!series) return;
    // 세로 스케일이 평단선을 포함하도록 값부터 기록한다(위 autoscaleInfoProvider가 읽는다).
    priceLineValuesRef.current = priceLines.map((spec) => toChartNumber(spec.price));
    const lines: IPriceLine[] = priceLines.map((spec) =>
      series.createPriceLine({
        price: toChartNumber(spec.price),
        color: spec.color,
        lineWidth: 2,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        title: spec.title,
      }),
    );
    return () => {
      // 위 disposedRef 주석 참고 — 차트가 이미 폐기됐으면 이 series에 더 접근하지 않는다.
      if (disposedRef.current) return;
      lines.forEach((line) => series.removePriceLine(line));
    };
  }, [priceLines]);

  // 높이는 배경·플롯 격자와 같은 24px 모듈의 배수로 잡는다(§4) — 375px에서 8모듈(192px),
  // sm 이상에서 12모듈(288px). 로딩 자리표시자(AssetDetailPage)도 같은 높이를 써서
  // 캔들이 도착하는 순간 카드 높이가 튀지 않는다.
  return <div ref={containerRef} data-testid={testId} className="h-48 w-full sm:h-72" />;
}
