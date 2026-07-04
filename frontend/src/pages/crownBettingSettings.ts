import { apiClient } from '../services/api'
import type { AutoBettingMode } from './crownBettingExecutionPlan'
import type { ApiResponse, AutomationSettings, NotificationConfigResponse, SystemConfigResponse } from './crownBettingTypes'

export const AUTOMATION_SETTINGS_STORAGE_KEY = 'crown-betting-automation-settings'
export const AUTO_BETTING_POLL_INTERVAL_MS = 5000
export const AUTO_BETTING_ACCOUNT_EXECUTION_TIMEOUT_MS = 30000

export const DEFAULT_AUTOMATION_SETTINGS: AutomationSettings = {
  autoMode: 'prematch',
  autoEnabled: false,
  perAccountLimit: 50,
  betLimit: 100,
  minimumBetOdds: 0.70,
  signalMaxAgeSeconds: 360,
}

export const automationModeOptions = [
  { label: '赛前', value: 'prematch' },
  { label: '滚球', value: 'live' },
]

export const normalizeNumberSetting = (
  value: unknown,
  fallback: number,
  min: number,
  max?: number,
) => {
  const numericValue = Number(value)
  if (!Number.isFinite(numericValue)) return fallback
  const lowerBoundedValue = Math.max(min, numericValue)
  return typeof max === 'number' ? Math.min(max, lowerBoundedValue) : lowerBoundedValue
}

export const readStoredAutomationSettings = (): AutomationSettings => {
  try {
    const raw = localStorage.getItem(AUTOMATION_SETTINGS_STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) as Partial<AutomationSettings> : {}
    return {
      autoMode: parsed.autoMode === 'live' ? 'live' : 'prematch',
      autoEnabled: typeof parsed.autoEnabled === 'boolean' ? parsed.autoEnabled : DEFAULT_AUTOMATION_SETTINGS.autoEnabled,
      perAccountLimit: normalizeNumberSetting(
        parsed.perAccountLimit,
        DEFAULT_AUTOMATION_SETTINGS.perAccountLimit,
        50,
        500,
      ),
      betLimit: normalizeNumberSetting(parsed.betLimit, DEFAULT_AUTOMATION_SETTINGS.betLimit, 10),
      minimumBetOdds: normalizeNumberSetting(parsed.minimumBetOdds, DEFAULT_AUTOMATION_SETTINGS.minimumBetOdds, 0.01),
      signalMaxAgeSeconds: normalizeNumberSetting(
        parsed.signalMaxAgeSeconds,
        DEFAULT_AUTOMATION_SETTINGS.signalMaxAgeSeconds,
        1,
        3600,
      ),
    }
  } catch {
    return DEFAULT_AUTOMATION_SETTINGS
  }
}

export const persistAutomationSettings = (settings: AutomationSettings) => {
  localStorage.setItem(AUTOMATION_SETTINGS_STORAGE_KEY, JSON.stringify(settings))
}

export const updateAutoBettingEnabled = (autoBettingEnabled: boolean) => (
  apiClient.post<ApiResponse<SystemConfigResponse>>(
    '/system/config/auto-betting-enabled/update',
    { autoBettingEnabled },
  )
)

export const monitorModeFromNotificationConfigs = (configs: NotificationConfigResponse[]): AutoBettingMode | null => {
  const monitorConfig = configs.find((config) => {
    if (config.enabled === false) return false
    const data = config.config?.data || config.config
    return data?.monitorModeEnabled === true
  })
  const data = monitorConfig?.config?.data || monitorConfig?.config
  if (!data || typeof data.liveOnlyModeEnabled !== 'boolean') {
    return null
  }
  return data.liveOnlyModeEnabled ? 'live' : 'prematch'
}

export const readMonitorMode = async (fallbackMode: AutoBettingMode): Promise<AutoBettingMode> => {
  try {
    const response = await apiClient.post<ApiResponse<NotificationConfigResponse[]>>(
      '/system/notifications/configs/list',
      {},
    )
    if (response.data.code !== 0 || !Array.isArray(response.data.data)) {
      return fallbackMode
    }
    return monitorModeFromNotificationConfigs(response.data.data) || fallbackMode
  } catch {
    return fallbackMode
  }
}

export const readBackendAutoBettingEnabled = async (): Promise<boolean> => {
  try {
    const response = await apiClient.post<ApiResponse<SystemConfigResponse>>('/system/config/get', {})
    return response.data.code === 0 && response.data.data?.autoBettingEnabled === true
  } catch {
    return false
  }
}
