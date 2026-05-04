import type { NotificationConfig } from '../types'

type TelegramConfigShape = {
  botToken?: string
  chatIds?: string[] | string
  monitorModeEnabled?: boolean
  liveOnlyModeEnabled?: boolean
  prematchWindowMinutes?: number | string | null
  marketBettingQueryEnabled?: boolean
  marketBettingDailyReportEnabled?: boolean
  marketBettingDailyReportTime?: string
  handicapCombinedWaterMin?: number | string | null
  totalCombinedWaterMin?: number | string | null
  handicapOddsMoveMin?: number | string | null
  totalOddsMoveMin?: number | string | null
  moneylineOddsMoveMin?: number | string | null
  copyTradingLeaderGroups?: string[]
  copyTradingCategories?: string[]
  copyTradingNotificationTypes?: string[]
}

export const extractTelegramConfig = (config: NotificationConfig): TelegramConfigShape => {
  if (!config.config) {
    return {}
  }

  if ('data' in config.config && config.config.data) {
    return (config.config as any).data ?? {}
  }

  return config.config as TelegramConfigShape
}

export const normalizeChatIds = (chatIds?: string[] | string): string[] => {
  if (Array.isArray(chatIds)) {
    return chatIds.filter((id) => id && String(id).trim())
  }

  if (typeof chatIds === 'string') {
    return chatIds
      .split(',')
      .map((id) => id.trim())
      .filter(Boolean)
  }

  return []
}

export const isTelegramConfigReadyForTest = (config: NotificationConfig): boolean => {
  const telegramConfig = extractTelegramConfig(config)
  const botToken = telegramConfig.botToken?.trim()

  return config.enabled && Boolean(botToken) && normalizeChatIds(telegramConfig.chatIds).length > 0
}
