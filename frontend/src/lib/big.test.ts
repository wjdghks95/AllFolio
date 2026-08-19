import { describe, expect, it } from 'vitest'
import { Dec, toScaledString } from './big'

describe('Dec', () => {
  it('primitive number 입력 시 strict 모드로 throw한다', () => {
    expect(() => Dec(60000)).toThrow()
  })

  it('string 입력은 정상 동작한다', () => {
    expect(() => Dec('60000')).not.toThrow()
  })
})

describe('toScaledString', () => {
  it('HALF_UP으로 반올림한다', () => {
    expect(toScaledString(Dec('58333.5'), 0)).toBe('58334')
  })
})
