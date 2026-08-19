import type { AssetType, Money } from '../api/types'
import { Dec, toScaledString } from './big'

export const SCALE = {
  KRW: 0,
  USD: 4,
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

export function formatAmount(
  value: Money | null,
  opts: { currency: string; assetType?: AssetType },
): string {
  if (value === null) return NULL_DISPLAY
  const scale = scaleFor(opts)
  // Intl.NumberFormat의 문자열 입력(ES2023 V3)에 기대지 않고 정규식으로 직접 그룹핑한다 —
  // 구형 엔진(Task 027 Capacitor 구형 WebView 등)에서 문자열 입력이 조용히 ToNumber로
  // 폴백되면 NUMERIC(28,8) 정밀도가 깨질 수 있다. toScaledString이 이미 정확한 소수
  // 자릿수를 만들어주므로 minimumFractionDigits 같은 옵션은 필요 없다.
  const scaled = toScaledString(Dec(value), scale)
  const negative = scaled.startsWith('-')
  const unsigned = negative ? scaled.slice(1) : scaled
  const [intPart, fracPart] = unsigned.split('.')
  const groupedInt = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const grouped = fracPart !== undefined ? `${groupedInt}.${fracPart}` : groupedInt
  return negative ? `-${grouped}` : grouped
}

export function formatQuantity(value: Money): string {
  const scaled = toScaledString(Dec(value), SCALE.QUANTITY)
  return scaled.includes('.') ? scaled.replace(/0+$/, '').replace(/\.$/, '') : scaled
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
