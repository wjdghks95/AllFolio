// 캔들 차트(CandlestickChart)가 쓰는 디자인 토큰의 거울. 캔버스 렌더러라 CSS 변수를 직접
// 참조할 수 없어 index.css의 @theme 값을 그대로 옮겨 적는다 — 토큰이 바뀌면 ui-ux-designer가
// 이 파일도 함께 갱신한다(docs/DESIGN.md §2). 별도 파일로 둔 이유는 CandlestickChart.tsx가
// 컴포넌트만 export하게 해 react-refresh(oxlint) 경고를 피하기 위함이다.
//
// 캔버스 안에서도 "격자지 위의 잉크"는 그대로다: 격자는 잉크 7%(§5 plot-grid 유틸리티와 같은
// 농도), 축 테두리는 rule, 축 글자는 보조 잉크. 차트가 제 색을 새로 짓지 않게 여기서 다 준다.
export const CHART_TOKEN = {
  surface: '#ffffff', // --color-surface — 플롯 배경
  rule: '#d8dfec', // --color-rule — 가격축·시간축 경계선
  inkSoft: '#5a6880', // --color-ink-soft — 축 눈금 글자·크로스헤어
  ink: '#0e1c31', // --color-ink — 크로스헤어 라벨 바탕
  // --color-ink 7%를 흰 표면 위에 얹은 값(= .plot-grid 유틸리티의 격자 농도).
  // rule(#d8dfec)을 격자에까지 쓰면 축 경계선과 같은 무게가 되어 플롯이 표처럼 보인다.
  plotGrid: '#eeeff1',
  // --font-mono. 축에 찍히는 값은 전부 숫자라 §3의 "모든 수치는 등폭" 규칙을 따른다.
  fontMono: '"Reddit Mono", "Gothic A1", ui-monospace, SFMono-Regular, monospace',
} as const;

// 평단선 톤 → 색. AssetDetailPage의 TONE_CLASS(text-gain/text-loss/text-ink)와 같은 매핑의
// 캔버스판이다 — 현재 평단선은 잉크, 예상 평단선은 방향(상승 빨강/하락 파랑)을 따른다(§2-2).
export const CHART_LINE_COLOR = {
  current: CHART_TOKEN.ink,
  gain: '#ce2e26', // --color-gain
  loss: '#1e5fd1', // --color-loss
  flat: CHART_TOKEN.ink,
} as const;
