import { apiClient } from '../../services/api'
import type { DashboardData, MatchDetail } from './types'

type ApiResponse<T> = { code: number; data: T; msg: string }

export const fetchOddsMonitorDashboard = async () => {
  const response = await apiClient.post<ApiResponse<DashboardData>>('/odds-monitor/dashboard', {})
  return response.data.data
}

export const fetchOddsMonitorMatchDetail = async (matchId: number) => {
  const response = await apiClient.post<ApiResponse<MatchDetail | null>>('/odds-monitor/match-detail', { matchId })
  return response.data.data
}
