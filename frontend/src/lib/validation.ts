// 폼 입력 검증. 문구는 절대 포함하지 않는다 — 실패 시 ValidationCode만 반환하고
// 실제 문구는 lib/messages.ts의 VALIDATION_MESSAGES에서 코드로 조회한다.
import type { AssetType } from '../api/types'
import { Dec } from './big'

export const LIMITS = {
  EMAIL_MAX: 255,
  PASSWORD_MIN: 8,
  PASSWORD_MAX_BYTES: 72,
  TICKER_MAX: 20,
  NAME_MAX: 100,
  QUANTITY_INT_DIGITS: 20,
  QUANTITY_SCALE: 8,
} as const

export type ValidationCode =
  | 'REQUIRED'
  | 'EMAIL_FORMAT'
  | 'EMAIL_TOO_LONG'
  | 'PASSWORD_TOO_SHORT'
  | 'PASSWORD_TOO_LONG_BYTES'
  | 'TICKER_LENGTH'
  | 'TICKER_WHITESPACE'
  | 'NAME_LENGTH'
  | 'CURRENCY_FORMAT'
  | 'NUMBER_FORMAT'
  | 'NUMBER_SCALE'
  | 'NUMBER_PRECISION'
  | 'PRICE_NOT_POSITIVE'
  | 'PRICE_FORBIDDEN_FOR_CASH'
  | 'QUANTITY_NOT_POSITIVE'

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const CURRENCY_REGEX = /^[A-Z]{3}$/
// 정수부 1~20자리 + (선택) 소수부 1~8자리. 부호·공백·과학표기 불허.
const DECIMAL_STRUCTURE_REGEX = /^\d+(\.\d+)?$/

// BCrypt 72바이트 한계 검증용 — UTF-16 코드유닛 수(v.length)가 아니라 실제 바이트 수를 잰다.
export function utf8ByteLength(v: string): number {
  return new TextEncoder().encode(v).length
}

export function validateEmail(v: string): ValidationCode | null {
  if (v.length === 0) return 'REQUIRED'
  if (v.length > LIMITS.EMAIL_MAX) return 'EMAIL_TOO_LONG'
  if (!EMAIL_REGEX.test(v)) return 'EMAIL_FORMAT'
  return null
}

export function validatePassword(v: string): ValidationCode | null {
  if (v.length === 0) return 'REQUIRED'
  if (v.length < LIMITS.PASSWORD_MIN) return 'PASSWORD_TOO_SHORT'
  if (utf8ByteLength(v) > LIMITS.PASSWORD_MAX_BYTES) return 'PASSWORD_TOO_LONG_BYTES'
  return null
}

// 길이는 v.length(UTF-16 코드유닛, Java String.length()와 같은 축)로 센다 — 바이트 수 아님.
export function validateTicker(v: string): ValidationCode | null {
  if (v.length === 0) return 'REQUIRED'
  if (/\s/.test(v)) return 'TICKER_WHITESPACE'
  if (v.length > LIMITS.TICKER_MAX) return 'TICKER_LENGTH'
  return null
}

export function validateName(v: string): ValidationCode | null {
  if (v.length === 0) return 'REQUIRED'
  if (v.length > LIMITS.NAME_MAX) return 'NAME_LENGTH'
  return null
}

export function validateCurrency(v: string): ValidationCode | null {
  if (v.length === 0) return 'REQUIRED'
  if (!CURRENCY_REGEX.test(v)) return 'CURRENCY_FORMAT'
  return null
}

// 자릿수 검증은 정규식으로만 판단한다 — big.js는 자릿수 제한이 아니라 ">0" 판정에만 쓴다.
function validateDecimalStructure(v: string): ValidationCode | null {
  if (!DECIMAL_STRUCTURE_REGEX.test(v)) return 'NUMBER_FORMAT'
  const [intPart, fracPart] = v.split('.')
  if (intPart.length > LIMITS.QUANTITY_INT_DIGITS) return 'NUMBER_PRECISION'
  if (fracPart !== undefined && fracPart.length > LIMITS.QUANTITY_SCALE) return 'NUMBER_SCALE'
  return null
}

export function validateQuantity(v: string): ValidationCode | null {
  if (v.length === 0) return 'REQUIRED'
  return validateDecimalStructure(v)
}

// 물타기 시뮬레이터(F006)의 추가 매수 수량 전용 — validateQuantity의 구조 검증에 더해
// 0을 허용하지 않는다(백엔드가 additionalQuantity > 0을 강제한다). validateQuantity 자체는
// 보유수량 등 0을 허용해야 하는 다른 곳에서 계속 쓰이므로 건드리지 않는다.
export function validateAdditionalQuantity(v: string): ValidationCode | null {
  const structureCode = validateQuantity(v)
  if (structureCode) return structureCode
  // strict 모드이므로 반드시 문자열 인자('0')를 넘긴다 — 숫자 리터럴 0은 throw.
  if (!Dec(v).gt('0')) return 'QUANTITY_NOT_POSITIVE'
  return null
}

// CASH는 avgPrice가 없어야 하고(null만 허용), 그 외 자산은 필수 + 자릿수 구조 + 양수여야 한다.
export function validateAvgPrice(v: string | null, assetType: AssetType): ValidationCode | null {
  if (assetType === 'CASH') {
    return v === null ? null : 'PRICE_FORBIDDEN_FOR_CASH'
  }
  if (v === null || v.length === 0) return 'REQUIRED'
  const structureCode = validateDecimalStructure(v)
  if (structureCode) return structureCode
  // strict 모드이므로 반드시 문자열 인자('0')를 넘긴다 — 숫자 리터럴 0은 throw.
  if (!Dec(v).gt('0')) return 'PRICE_NOT_POSITIVE'
  return null
}
