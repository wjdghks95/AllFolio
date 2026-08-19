import { Big } from 'big.js'

// Big()을 new 없이 호출 → 독립 생성자, 전역 설정 오염 방지
export const Dec = Big()
Dec.DP = 30 // div 중간 계산 소수 30자리 (목표 scale 최대 8보다 충분히 커서 이중 반올림 무해)
Dec.RM = Dec.roundHalfUp
Dec.strict = true // primitive number 입력 시 throw → Number() 금지 규칙을 런타임에서 강제
Dec.NE = -30
Dec.PE = 40 // toString 지수표기 방지

export type BigNum = Big

export function toScaledString(value: BigNum, scale: number): string {
  return value.toFixed(scale, Dec.roundHalfUp)
}
