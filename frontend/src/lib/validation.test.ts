import { describe, expect, it } from 'vitest'
import {
  LIMITS,
  utf8ByteLength,
  validateAvgPrice,
  validateCurrency,
  validateEmail,
  validateName,
  validatePassword,
  validateQuantity,
  validateTicker,
} from './validation'

describe('utf8ByteLength', () => {
  it('한글 1자는 3바이트다', () => {
    expect(utf8ByteLength('가')).toBe(3)
  })

  it('ASCII 1자는 1바이트다', () => {
    expect(utf8ByteLength('a')).toBe(1)
  })
})

describe('validateEmail', () => {
  it('빈 문자열은 REQUIRED', () => {
    expect(validateEmail('')).toBe('REQUIRED')
  })

  it('형식이 올바르지 않으면 EMAIL_FORMAT', () => {
    expect(validateEmail('not-an-email')).toBe('EMAIL_FORMAT')
  })

  it('255자 경계는 통과한다', () => {
    const email = `${'a'.repeat(243)}@example.com`
    expect(email.length).toBe(255)
    expect(validateEmail(email)).toBeNull()
  })

  it('256자는 EMAIL_TOO_LONG', () => {
    const email = `${'a'.repeat(244)}@example.com`
    expect(email.length).toBe(256)
    expect(validateEmail(email)).toBe('EMAIL_TOO_LONG')
  })
})

describe('validatePassword', () => {
  it('7자는 PASSWORD_TOO_SHORT', () => {
    expect(validatePassword('a'.repeat(7))).toBe('PASSWORD_TOO_SHORT')
  })

  it('8자는 통과한다', () => {
    expect(validatePassword('a'.repeat(8))).toBeNull()
  })

  it('ASCII 72바이트 경계는 통과한다', () => {
    expect(validatePassword('a'.repeat(72))).toBeNull()
  })

  it('ASCII 73바이트는 PASSWORD_TOO_LONG_BYTES', () => {
    expect(validatePassword('a'.repeat(73))).toBe('PASSWORD_TOO_LONG_BYTES')
  })

  it('한글 24자(72바이트)는 통과한다', () => {
    const v = '가'.repeat(24)
    expect(utf8ByteLength(v)).toBe(72)
    expect(validatePassword(v)).toBeNull()
  })

  it('한글 25자(75바이트)는 PASSWORD_TOO_LONG_BYTES', () => {
    const v = '가'.repeat(25)
    expect(utf8ByteLength(v)).toBe(75)
    expect(validatePassword(v)).toBe('PASSWORD_TOO_LONG_BYTES')
  })
})

describe('validateTicker', () => {
  it('공백이 포함되면 TICKER_WHITESPACE', () => {
    expect(validateTicker('AB C')).toBe('TICKER_WHITESPACE')
  })

  it('20자 경계는 통과한다', () => {
    expect(validateTicker('a'.repeat(LIMITS.TICKER_MAX))).toBeNull()
  })

  it('21자는 TICKER_LENGTH', () => {
    expect(validateTicker('a'.repeat(LIMITS.TICKER_MAX + 1))).toBe('TICKER_LENGTH')
  })
})

describe('validateName', () => {
  it('100자 경계는 통과한다', () => {
    expect(validateName('a'.repeat(LIMITS.NAME_MAX))).toBeNull()
  })

  it('101자는 NAME_LENGTH', () => {
    expect(validateName('a'.repeat(LIMITS.NAME_MAX + 1))).toBe('NAME_LENGTH')
  })
})

describe('validateCurrency', () => {
  it('소문자는 CURRENCY_FORMAT', () => {
    expect(validateCurrency('krw')).toBe('CURRENCY_FORMAT')
  })

  it('4자는 CURRENCY_FORMAT', () => {
    expect(validateCurrency('KRWW')).toBe('CURRENCY_FORMAT')
  })

  it('대문자 3자는 통과한다', () => {
    expect(validateCurrency('KRW')).toBeNull()
  })
})

describe('validateQuantity', () => {
  it('소수 8자리는 통과한다', () => {
    expect(validateQuantity('1.12345678')).toBeNull()
  })

  it('소수 9자리는 NUMBER_SCALE', () => {
    expect(validateQuantity('1.123456789')).toBe('NUMBER_SCALE')
  })

  it('정수부 20자리 경계는 통과한다', () => {
    expect(validateQuantity('1'.repeat(LIMITS.QUANTITY_INT_DIGITS))).toBeNull()
  })

  it('정수부 21자리는 NUMBER_PRECISION', () => {
    expect(validateQuantity('1'.repeat(LIMITS.QUANTITY_INT_DIGITS + 1))).toBe('NUMBER_PRECISION')
  })

  it('음수는 NUMBER_FORMAT', () => {
    expect(validateQuantity('-5')).toBe('NUMBER_FORMAT')
  })

  it("'0'은 허용한다", () => {
    expect(validateQuantity('0')).toBeNull()
  })
})

describe('validateAvgPrice', () => {
  it('CASH + null은 통과한다', () => {
    expect(validateAvgPrice(null, 'CASH')).toBeNull()
  })

  it('CASH + 값은 PRICE_FORBIDDEN_FOR_CASH', () => {
    expect(validateAvgPrice('1', 'CASH')).toBe('PRICE_FORBIDDEN_FOR_CASH')
  })

  it('STOCK + null은 REQUIRED', () => {
    expect(validateAvgPrice(null, 'STOCK')).toBe('REQUIRED')
  })

  it("STOCK + '0'은 PRICE_NOT_POSITIVE (0은 양수 아님)", () => {
    expect(validateAvgPrice('0', 'STOCK')).toBe('PRICE_NOT_POSITIVE')
  })

  it("STOCK + '0.0001'은 통과한다", () => {
    expect(validateAvgPrice('0.0001', 'STOCK')).toBeNull()
  })
})
