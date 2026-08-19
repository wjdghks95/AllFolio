// 물타기 시뮬레이터 순수 계산 (F006). 백엔드 POST /v1/simulate/avg-price와 동일한 규칙을
// 프론트에서도 재현해 화면에 즉시 반영한다 — 실제 저장은 하지 않는다.
import type { AssetType, Money } from '../api/types'
import { Dec, toScaledString } from './big'
import { SCALE, scaleFor } from './money'

export interface SimulateInput {
  currentQuantity: Money
  currentAvgPrice: Money
  additionalQuantity: Money
  additionalPrice: Money
  assetType: AssetType
  currency: string
}

export interface SimulateResult {
  currentAvgPrice: Money
  expectedAvgPrice: Money
  expectedQuantity: Money
  currentWeight: null
  expectedWeight: null
}

export function simulateAvgPrice(input: SimulateInput): SimulateResult {
  const currentQuantity = Dec(input.currentQuantity)
  const currentAvgPrice = Dec(input.currentAvgPrice)
  const additionalQuantity = Dec(input.additionalQuantity)
  const additionalPrice = Dec(input.additionalPrice)

  const totalQuantity = currentQuantity.plus(additionalQuantity)
  if (totalQuantity.eq('0')) {
    throw new RangeError('총수량이 0입니다 — 페이지가 먼저 막아야 하는 입력입니다.')
  }

  // plus/times는 big.js 정확한 십진 연산이라 무반올림. div만 Dec.DP(30자리)로 계산 후
  // 목표 scale로 HALF_UP 반올림한다.
  const totalCost = currentQuantity.times(currentAvgPrice).plus(additionalQuantity.times(additionalPrice))
  const expectedAvgPriceRaw = totalCost.div(totalQuantity)

  const priceScale = scaleFor({ currency: input.currency, assetType: input.assetType })

  return {
    currentAvgPrice: input.currentAvgPrice,
    expectedAvgPrice: toScaledString(expectedAvgPriceRaw, priceScale),
    // 수량은 통화/자산 종류와 무관하게 항상 8자리 (money.ts의 SCALE.QUANTITY와 동일 축).
    expectedQuantity: toScaledString(totalQuantity, SCALE.QUANTITY),
    currentWeight: null,
    expectedWeight: null,
  }
}
