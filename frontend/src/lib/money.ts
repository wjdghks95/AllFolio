import type { AssetType, Money } from '../api/types'
import { Dec, toScaledString } from './big'

export const SCALE = {
  KRW: 0,
  USD: 2,
  COIN: 8,
  WEIGHT: 2,
  QUANTITY: 8,
  FALLBACK: 2,
} as const

export const NULL_DISPLAY = '—'

export function scaleFor(opts: { currency: string; assetType?: AssetType }): number {
  if (opts.assetType === 'COIN') return SCALE.COIN
  if (opts.currency === 'KRW') return SCALE.KRW
  if (opts.currency === 'USD') return SCALE.USD
  return SCALE.FALLBACK
}

// Intl.NumberFormat의 문자열 입력(ES2023 V3)에 기대지 않고 정규식으로 직접 그룹핑한다 —
// 구형 엔진(Task 027 Capacitor 구형 WebView 등)에서 문자열 입력이 조용히 ToNumber로
// 폴백되면 NUMERIC(28,8) 정밀도가 깨질 수 있다. 입력은 항상 부호 없는 십진 문자열이다.
function groupThousands(unsignedDecimal: string): string {
  const [intPart, fracPart] = unsignedDecimal.split('.')
  const groupedInt = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return fracPart !== undefined ? `${groupedInt}.${fracPart}` : groupedInt
}

// 정수처럼 보이는 값의 무의미한 후행 0을 지운다(20.00000000 → 20, 10.50000000 → 10.5) —
// 실제로 그만큼 소수 자리가 있는 값은 그대로 남긴다.
function trimTrailingZeros(scaled: string): string {
  return scaled.includes('.') ? scaled.replace(/0+$/, '').replace(/\.$/, '') : scaled
}

export function formatAmount(
  value: Money | null,
  opts: { currency: string; assetType?: AssetType },
): string {
  if (value === null) return NULL_DISPLAY
  const scale = scaleFor(opts)
  // toScaledString이 이미 정확한 소수 자릿수를 만들어주므로 minimumFractionDigits 같은
  // 옵션은 필요 없다.
  const scaled = toScaledString(Dec(value), scale)
  // COIN 가격(평단가·취득원가 등)은 종목마다 자릿수가 들쭉날쭉해, 8자리 고정 표시가
  // "100,000,000.00000000"처럼 무의미한 후행 0을 달고 나온다 — COIN에 한해서만 지운다.
  // KRW(스케일 0)는 애초에 소수점이 없고, USD는 의도적으로 스케일 2 고정 표시를 유지한다
  // (money.test.ts formatAmount USD 케이스 — 자릿수를 흔들면 시세 비교가 어려워진다).
  const digits = opts.assetType === 'COIN' ? trimTrailingZeros(scaled) : scaled
  const negative = digits.startsWith('-')
  const grouped = groupThousands(negative ? digits.slice(1) : digits)
  return negative ? `-${grouped}` : grouped
}

export function formatQuantity(value: Money): string {
  const scaled = toScaledString(Dec(value), SCALE.QUANTITY)
  // 수량은 검증 단계(lib/validation.ts)에서 항상 0 이상만 허용하므로 부호 처리가 필요 없다.
  return groupThousands(trimTrailingZeros(scaled))
}

// formatQuantity/formatAmount와 같은 후행 0 트리밍을 쓰되, 천 단위 구분 쉼표는 넣지 않는다 —
// 이 값은 TextField에 그대로 채워 넣는 편집 입력값이라, 쉼표가 들어가면
// lib/validation.ts의 DECIMAL_STRUCTURE_REGEX(순수 십진 문자열만 허용)를 통과하지 못한다.
// NUMERIC(28,8) 컬럼이 항상 소수 8자리로 내려주는 백엔드 값(예: "20.00000000")을 그대로
// 채워 넣으면 주식·현금처럼 실제로는 정수인 값에도 코인과 같은 소수점이 보인다.
export function toEditableQuantity(value: Money): string {
  return trimTrailingZeros(toScaledString(Dec(value), SCALE.QUANTITY))
}

// scaleFor(opts)로 반올림하면 안 된다 — 예를 들어 KRW는 화면 표시 스케일이 0이라
// "70000.5" 같은 실제 저장값이 수정 입력란에 "70001"로 반올림되어 채워지고, 사용자가
// 다른 필드만 고쳐 그대로 제출하면 평단가가 조용히 바뀌는 데이터 손실이 된다
// (code-reviewer M3 지적). 컬럼 자체가 NUMERIC(28,8)이라 스케일 8로 트리밍하면
// 반올림 없이 원본 값 그대로, 무의미한 후행 0만 지운다.
export function toEditableAmount(value: Money): string {
  return trimTrailingZeros(toScaledString(Dec(value), SCALE.QUANTITY))
}

export function formatWeight(value: Money | null): string {
  if (value === null) return NULL_DISPLAY
  return `${toScaledString(Dec(value), SCALE.WEIGHT)}%`
}

export function formatSignedAmount(
  value: Money | null,
  opts: { currency: string; assetType?: AssetType },
): { text: string; tone: 'gain' | 'loss' | 'flat' | 'unknown' } {
  if (value === null) return { text: NULL_DISPLAY, tone: 'unknown' }
  const scale = scaleFor(opts)
  // tone은 표시 텍스트와 같은 반올림 후 값 기준으로 판정한다 — 반올림 전 값으로 판정하면
  // KRW(scale 0)에서 '0.4' 같은 값이 반올림 후 '0'으로 보이는데도 '+0'처럼 부호가 남는다.
  const scaled = Dec(toScaledString(Dec(value), scale))
  if (scaled.eq('0')) {
    return { text: formatAmount(value.replace(/^-/, ''), opts), tone: 'flat' }
  }
  const text = formatAmount(value, opts)
  return scaled.gt('0') ? { text: `+${text}`, tone: 'gain' } : { text, tone: 'loss' }
}
