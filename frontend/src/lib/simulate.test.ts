import { describe, expect, it } from 'vitest'
import { simulateAvgPriceFixture } from '../api/fixtures'
import { simulateAvgPrice } from './simulate'

describe('simulateAvgPrice', () => {
  it('ROADMAP 골든 케이스: KRW scale 0, 60,000원×10주 + 55,000원×5주 → 58,333원', () => {
    const result = simulateAvgPrice({
      currentQuantity: '10',
      currentAvgPrice: '60000',
      additionalQuantity: '5',
      additionalPrice: '55000',
      assetType: 'STOCK',
      currency: 'KRW',
    })

    expect(result.currentAvgPrice).toBe(simulateAvgPriceFixture.currentAvgPrice)
    expect(result.expectedAvgPrice).toBe(simulateAvgPriceFixture.expectedAvgPrice)
    expect(result.expectedAvgPrice).toBe('58333')
    expect(result.expectedQuantity).toBe('15.00000000')
  })

  it('COIN은 8자리를 유지한다', () => {
    const result = simulateAvgPrice({
      currentQuantity: '0.05123456',
      currentAvgPrice: '82000000',
      additionalQuantity: '0.01',
      additionalPrice: '90000000',
      assetType: 'COIN',
      currency: 'KRW',
    })

    // (0.05123456×82,000,000 + 0.01×90,000,000) / 0.06123456
    // = (4,201,233.92 + 900,000) / 0.06123456 = 5,101,233.92 / 0.06123456
    expect(result.expectedAvgPrice.split('.')[1]?.length ?? 0).toBe(8)
    expect(result.expectedQuantity).toBe('0.06123456')
  })

  it('USD는 4자리를 유지한다', () => {
    const result = simulateAvgPrice({
      currentQuantity: '12',
      currentAvgPrice: '182.5000',
      additionalQuantity: '3',
      additionalPrice: '190.0000',
      assetType: 'STOCK',
      currency: 'USD',
    })

    // (12×182.5 + 3×190) / 15 = (2190 + 570) / 15 = 2760 / 15 = 184
    expect(result.expectedAvgPrice).toBe('184.0000')
    expect(result.expectedQuantity).toBe('15.00000000')
  })

  it('currentWeight/expectedWeight는 항상 null이다', () => {
    const result = simulateAvgPrice({
      currentQuantity: '10',
      currentAvgPrice: '60000',
      additionalQuantity: '5',
      additionalPrice: '55000',
      assetType: 'STOCK',
      currency: 'KRW',
    })

    expect(result.currentWeight).toBeNull()
    expect(result.expectedWeight).toBeNull()
  })

  it('총수량이 0이면 RangeError를 던진다', () => {
    expect(() =>
      simulateAvgPrice({
        currentQuantity: '5',
        currentAvgPrice: '100',
        additionalQuantity: '-5',
        additionalPrice: '100',
        assetType: 'STOCK',
        currency: 'KRW',
      }),
    ).toThrow(RangeError)
  })
})
