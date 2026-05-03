import { apiService } from '../services/api'
import type { CopyTrading, CopyTradingStatistics } from '../types'

export async function loadCopyTradingStatisticsMap(
  list: Array<Pick<CopyTrading, 'id'>>,
): Promise<Record<number, CopyTradingStatistics>> {
  const copyTradingIds = Array.from(new Set(list.map((item) => item.id).filter((id) => id > 0)))
  if (copyTradingIds.length === 0) {
    return {}
  }

  try {
    const response = await apiService.statistics.batchDetail({ copyTradingIds })
    if (response.data.code !== 0 || !response.data.data) {
      return {}
    }

    const nextMap: Record<number, CopyTradingStatistics> = {}
    response.data.data.list.forEach((item) => {
      nextMap[item.copyTradingId] = item
    })
    return nextMap
  } catch {
    return {}
  }
}
