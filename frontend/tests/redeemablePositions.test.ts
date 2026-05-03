import { describe, expect, it } from 'vitest'

import { buildRedeemablePositionsSummary } from '../src/utils/redeemablePositions'
import type { AccountPosition } from '../src/types'

function createPosition(overrides: Partial<AccountPosition>): AccountPosition {
  return {
    accountId: 1,
    accountName: 'Demo Account',
    walletAddress: '0x1234567890123456789012345678901234567890',
    proxyAddress: '0x1234567890123456789012345678901234567891',
    marketId: 'market-1',
    marketTitle: 'Demo Market',
    marketSlug: 'demo-market',
    eventSlug: 'demo-event',
    marketIcon: undefined,
    side: 'YES',
    outcomeIndex: 0,
    quantity: '0.75',
    originalQuantity: '0.75',
    avgPrice: '0.5',
    currentPrice: '1',
    currentValue: '0.75',
    initialValue: '0.375',
    pnl: '0.375',
    percentPnl: '100',
    realizedPnl: undefined,
    percentRealizedPnl: undefined,
    redeemable: true,
    mergeable: false,
    endDate: undefined,
    isCurrent: true,
    ...overrides
  }
}

describe('buildRedeemablePositionsSummary', () => {
  it('keeps only redeemable positions for the selected account and sums value precisely', () => {
    const summary = buildRedeemablePositionsSummary([
      createPosition({ accountId: 1, marketId: 'market-1', quantity: '0.1' }),
      createPosition({ accountId: 1, marketId: 'market-2', quantity: '0.2', outcomeIndex: 1, side: 'NO' }),
      createPosition({ accountId: 2, marketId: 'market-3', quantity: '0.3' }),
      createPosition({ accountId: 1, marketId: 'market-4', redeemable: false, quantity: '9.9' })
    ], 1)

    expect(summary.totalCount).toBe(2)
    expect(summary.totalValue).toBe('0.3')
    expect(summary.positions).toEqual([
      {
        accountId: 1,
        accountName: 'Demo Account',
        marketId: 'market-1',
        marketTitle: 'Demo Market',
        side: 'YES',
        outcomeIndex: 0,
        quantity: '0.1',
        value: '0.1'
      },
      {
        accountId: 1,
        accountName: 'Demo Account',
        marketId: 'market-2',
        marketTitle: 'Demo Market',
        side: 'NO',
        outcomeIndex: 1,
        quantity: '0.2',
        value: '0.2'
      }
    ])
  })

  it('returns an empty summary when nothing can be redeemed', () => {
    const summary = buildRedeemablePositionsSummary([
      createPosition({ redeemable: false }),
      createPosition({ accountId: 2, redeemable: false })
    ])

    expect(summary).toEqual({
      totalCount: 0,
      totalValue: '0',
      positions: []
    })
  })
})
