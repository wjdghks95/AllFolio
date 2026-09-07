import { describe, expect, it } from 'vitest'
import {
  formatAmount,
  formatQuantity,
  formatSignedAmount,
  formatWeight,
  NULL_DISPLAY,
  scaleFor,
  toEditableAmount,
  toEditableQuantity,
} from './money'

describe('scaleFor', () => {
  it('assetType COIN이 currency보다 우선한다', () => {
    expect(scaleFor({ currency: 'KRW', assetType: 'COIN' })).toBe(8)
  })

  it('KRW는 0', () => {
    expect(scaleFor({ currency: 'KRW' })).toBe(0)
  })

  it('USD는 2', () => {
    expect(scaleFor({ currency: 'USD' })).toBe(2)
  })

  it('미지 통화는 2로 폴백한다 (throw 금지)', () => {
    expect(scaleFor({ currency: 'JPY' })).toBe(2)
  })
})

describe('formatAmount', () => {
  it('KRW는 정수로 그룹핑한다 (HALF_UP)', () => {
    expect(formatAmount('6476733.92', { currency: 'KRW' })).toBe('6,476,734')
  })

  it('USD는 소수 2자리를 유지한다', () => {
    expect(formatAmount('182.5', { currency: 'USD' })).toBe('182.50')
  })

  it('COIN은 소수부가 있으면 8자리를 그대로 유지한다', () => {
    expect(formatAmount('0.05123456', { currency: 'USD', assetType: 'COIN' })).toBe('0.05123456')
  })

  it('COIN이라도 값이 정수면 후행 0을 지운다', () => {
    expect(formatAmount('100000000', { currency: 'KRW', assetType: 'COIN' })).toBe('100,000,000')
  })

  it('COIN은 소수부가 있어도 후행 0만 지운다 (정규식이 소수부까지 지우면 이 값에서 잡힌다)', () => {
    expect(formatAmount('10.50000000', { currency: 'KRW', assetType: 'COIN' })).toBe('10.5')
  })

  it('null은 — 를 반환한다', () => {
    expect(formatAmount(null, { currency: 'KRW' })).toBe(NULL_DISPLAY)
  })

  it('20자리 정수도 정밀도 손실 없이 그룹핑한다', () => {
    expect(formatAmount('12345678901234567890', { currency: 'KRW' })).toBe(
      '12,345,678,901,234,567,890',
    )
  })
})

describe('formatQuantity', () => {
  it('뒤쪽 0을 제거한다', () => {
    expect(formatQuantity('10.00000000')).toBe('10')
  })

  it('정수부를 천 단위로 그룹핑한다', () => {
    expect(formatQuantity('1500000')).toBe('1,500,000')
  })

  it('그룹핑 후에도 소수부는 그대로 유지한다', () => {
    expect(formatQuantity('0.05123456')).toBe('0.05123456')
  })
})

describe('toEditableQuantity', () => {
  it('후행 0을 지우되 값은 반올림하지 않는다', () => {
    expect(toEditableQuantity('20.00000000')).toBe('20')
    expect(toEditableQuantity('10.50000000')).toBe('10.5')
  })
})

describe('toEditableAmount', () => {
  it('통화별 표시 스케일로 반올림하지 않는다 — KRW/USD도 소수 원본값을 그대로 보존한다', () => {
    // code-reviewer M3 회귀 테스트. scaleFor 기준(KRW 0/USD 2)으로 반올림하면
    // "70000.5" -> "70001", "182.5555" -> "182.56"처럼 수정 폼에 반올림된 값이 채워지고,
    // 사용자가 그 값을 그대로 제출하면 평단가가 조용히 바뀌는 데이터 손실이 된다.
    expect(toEditableAmount('70000.50000000')).toBe('70000.5')
    expect(toEditableAmount('182.55550000')).toBe('182.5555')
  })

  it('후행 0만 지운다', () => {
    expect(toEditableAmount('80000000.00000000')).toBe('80000000')
    expect(toEditableAmount('182.50000000')).toBe('182.5')
  })
})

describe('formatWeight', () => {
  it('HALF_UP으로 반올림한다 (number.toFixed 오차와 대조)', () => {
    // 1.005는 double로 표현하면 1.00499999999999989...가 되어 네이티브 toFixed는
    // HALF_UP이 아닌 절삭에 가까운 결과('1.00')를 낸다. big.js는 문자열 '1.005'를
    // 오차 없는 정확한 십진수로 다뤄 HALF_UP 규칙대로 올림한다.
    expect((1.005).toFixed(2)).toBe('1.00')
    expect(formatWeight('1.005')).toBe('1.01%')
  })

  it('null은 — 를 반환한다', () => {
    expect(formatWeight(null)).toBe(NULL_DISPLAY)
  })
})

describe('formatSignedAmount', () => {
  it('양수는 + 부호와 gain 톤을 반환한다', () => {
    expect(formatSignedAmount('100', { currency: 'KRW' })).toEqual({
      text: '+100',
      tone: 'gain',
    })
  })

  it('음수는 loss 톤을 반환한다', () => {
    expect(formatSignedAmount('-100', { currency: 'KRW' })).toEqual({
      text: '-100',
      tone: 'loss',
    })
  })

  it('0은 flat 톤을 반환한다', () => {
    expect(formatSignedAmount('0', { currency: 'KRW' })).toEqual({
      text: '0',
      tone: 'flat',
    })
  })

  it('null은 unknown 톤을 반환한다', () => {
    expect(formatSignedAmount(null, { currency: 'KRW' })).toEqual({
      text: NULL_DISPLAY,
      tone: 'unknown',
    })
  })

  it('회귀: 반올림 후 0이 되는 값은 부호 없는 flat 톤을 반환한다', () => {
    // KRW(scale 0)에서 '0.4'/'-0.4'는 반올림하면 둘 다 사실상 0이다. 반올림 전 원본 값으로
    // tone을 판정하면 '+0'/'-0'처럼 표시 텍스트와 어긋나는 부호가 남는다.
    expect(formatSignedAmount('0.4', { currency: 'KRW' })).toEqual({
      text: '0',
      tone: 'flat',
    })
    expect(formatSignedAmount('-0.4', { currency: 'KRW' })).toEqual({
      text: '0',
      tone: 'flat',
    })
  })
})
