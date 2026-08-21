// 문구 매핑 전용 파일. 코드(ValidationCode/ErrorCode) → 문구 변환만 담당한다.
// 문구 원칙(docs/DESIGN.md 「UI 카피 원칙」): 무엇이 잘못됐는지와 어떻게 고치는지를 한 문장에 담고,
// 사과하지 않으며, 한계값은 숫자로 밝힌다. 키는 타입과 1:1이므로 절대 바꾸지 않는다.
// (예외: HOLDING_CONFLICT는 ROADMAP 결정에 따라 고정 문구 — 임의 변경 금지)
import type { ErrorCode } from '../api/types'
import type { ValidationCode } from './validation'

export const VALIDATION_MESSAGES: Record<ValidationCode, string> = {
  REQUIRED: '입력이 필요합니다.',
  EMAIL_FORMAT: '이메일을 name@example.com 형식으로 입력하세요.',
  EMAIL_TOO_LONG: '이메일은 255자까지 입력할 수 있습니다.',
  PASSWORD_TOO_SHORT: '비밀번호를 8자 이상 입력하세요.',
  PASSWORD_TOO_LONG_BYTES: '비밀번호는 72바이트까지 입력할 수 있습니다. 한글은 한 자가 3바이트입니다.',
  TICKER_LENGTH: '티커는 20자까지 입력할 수 있습니다.',
  TICKER_WHITESPACE: '티커에 공백은 넣을 수 없습니다.',
  NAME_LENGTH: '종목명은 100자까지 입력할 수 있습니다.',
  CURRENCY_FORMAT: '통화는 KRW처럼 영문 대문자 3자리로 입력하세요.',
  NUMBER_FORMAT: '숫자만 입력하세요. 쉼표·공백·부호는 넣을 수 없습니다.',
  NUMBER_SCALE: '소수점 아래는 8자리까지 입력할 수 있습니다.',
  NUMBER_PRECISION: '정수 부분은 20자리까지 입력할 수 있습니다.',
  PRICE_NOT_POSITIVE: '평단가는 0보다 커야 합니다.',
  PRICE_FORBIDDEN_FOR_CASH: '현금 자산은 평단가를 입력하지 않습니다.',
}

export const ERROR_MESSAGES: Record<ErrorCode, string> = {
  EMAIL_ALREADY_EXISTS: '이미 가입된 이메일입니다. 로그인하거나 다른 이메일로 가입하세요.',
  INVALID_CREDENTIALS: '이메일 또는 비밀번호가 일치하지 않습니다.',
  UNAUTHORIZED: '로그인이 필요합니다. 다시 로그인하세요.',
  VALIDATION_ERROR: '입력값을 다시 확인하세요.',
  NOT_FOUND: '요청한 정보를 찾을 수 없습니다.',
  METHOD_NOT_ALLOWED: '처리할 수 없는 요청입니다.',
  UNSUPPORTED_MEDIA_TYPE: '지원하지 않는 요청 형식입니다.',
  NOT_ACCEPTABLE: '지원하지 않는 응답 형식입니다.',
  CLIENT_ERROR: '요청을 처리하지 못했습니다. 입력값을 확인하고 다시 시도하세요.',
  INTERNAL_ERROR: '서버에 문제가 생겼습니다. 잠시 후 다시 시도하세요.',
  ASSET_NOT_FOUND: '삭제되었거나 없는 자산입니다. 총 자산 화면에서 다시 선택하세요.',
  HOLDING_CONFLICT: '다른 곳에서 이미 수정되었습니다. 새로고침 후 다시 시도하세요.',
  CONFLICT: '지금 상태에서는 처리할 수 없습니다. 새로고침 후 다시 시도하세요.',
}

const FALLBACK_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.'

export function messageForErrorCode(code: string): string {
  return Object.hasOwn(ERROR_MESSAGES, code)
    ? ERROR_MESSAGES[code as ErrorCode]
    : FALLBACK_ERROR_MESSAGE
}
