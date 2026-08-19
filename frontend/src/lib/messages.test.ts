import { describe, expect, it } from 'vitest'
import { ERROR_MESSAGES, messageForErrorCode } from './messages'

describe('messageForErrorCode', () => {
  it('알려진 코드는 매핑된 문구를 반환한다', () => {
    expect(messageForErrorCode('VALIDATION_ERROR')).toBe(ERROR_MESSAGES.VALIDATION_ERROR)
  })

  it('HOLDING_CONFLICT는 ROADMAP 고정 문구를 반환한다', () => {
    expect(messageForErrorCode('HOLDING_CONFLICT')).toBe(
      '다른 곳에서 이미 수정되었습니다. 새로고침 후 다시 시도하세요.',
    )
  })

  it('모르는 코드는 폴백 문구를 반환한다', () => {
    expect(messageForErrorCode('NOT_A_REAL_CODE')).toBe(
      '요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.',
    )
  })

  it('회귀: Object.prototype에 존재하는 이름을 코드로 넘겨도 폴백 문구를 반환한다', () => {
    // `code in ERROR_MESSAGES`로 판정하면 프로토타입 체인까지 참조해 'toString' 같은
    // 입력이 폴백을 우회하고 Function을 반환할 수 있었다 (실제 버그, 수정됨).
    expect(messageForErrorCode('toString')).toBe(
      '요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.',
    )
  })
})
