import { describe, expect, it, vi, beforeEach } from 'vitest'

import type { CopyTrading, CopyTradingStatistics } from '../src/types'
import { loadCopyTradingStatisticsMap } from '../src/utils/copyTradingStatistics'
import { apiService } from '../src/services/api'

vi.mock('../src/services/api', () => ({
  apiService: {
    statistics: {
      batchDetail: vi.fn(),
    },
  },
}))

function createCopyTrading(id: number): CopyTrading {
  return {
    id,
    accountId: 1,
    accountName: 'Demo Account',
    walletAddress: '0x1234567890123456789012345678901234567890',
    leaderId: 2,
    leaderName: 'Demo Leader',
    leaderAddress: '0xabcdefabcdefabcdefabcdefabcdefabcdefabcd',
    enabled: true,
    followSettingsEnabled: true,
    maxOrderSize: '100',
    minOrderSize: '1',
    maxDailyLoss: '10',
    maxDailyOrders: 20,
    priceTolerance: '0.01',
    delaySeconds: 0,
    pollIntervalSeconds: 5,
    useWebSocket: true,
    websocketReconnectInterval: 3,
    websocketMaxRetries: 5,
    supportSell: true,
    pushFailedOrders: true,
    pushFilteredOrders: false,
    createdAt: 1,
    updatedAt: 1,
  }
}

function createStatistics(copyTradingId: number): CopyTradingStatistics {
  return {
    copyTradingId,
    accountId: 1,
    accountName: 'Demo Account',
    leaderId: 2,
    leaderName: 'Demo Leader',
    enabled: true,
    totalBuyQuantity: '1',
    totalBuyOrders: 1,
    totalBuyAmount: '1',
    avgBuyPrice: '1',
    totalSellQuantity: '0',
    totalSellOrders: 0,
    totalSellAmount: '0',
    currentPositionQuantity: '1',
    currentPositionValue: '0',
    totalRealizedPnl: '0',
    totalUnrealizedPnl: '0',
    totalPnl: '0',
    totalPnlPercent: '0',
  }
}

describe('loadCopyTradingStatisticsMap', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loads all list statistics with a single batch request', async () => {
    vi.mocked(apiService.statistics.batchDetail).mockResolvedValue({
      data: {
        code: 0,
        msg: '',
        data: {
          list: [createStatistics(1), createStatistics(2), createStatistics(3)],
        },
      },
    } as Awaited<ReturnType<typeof apiService.statistics.batchDetail>>)

    const result = await loadCopyTradingStatisticsMap([
      createCopyTrading(1),
      createCopyTrading(2),
      createCopyTrading(3),
    ])

    expect(apiService.statistics.batchDetail).toHaveBeenCalledTimes(1)
    expect(apiService.statistics.batchDetail).toHaveBeenCalledWith({ copyTradingIds: [1, 2, 3] })
    expect(Object.keys(result)).toEqual(['1', '2', '3'])
  })

  it('returns an empty map when there is nothing to load', async () => {
    const result = await loadCopyTradingStatisticsMap([])

    expect(apiService.statistics.batchDetail).not.toHaveBeenCalled()
    expect(result).toEqual({})
  })
})
