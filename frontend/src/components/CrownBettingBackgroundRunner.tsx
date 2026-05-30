import { useEffect, useRef } from 'react'
import { apiClient } from '../services/api'
import type { ApiResponse } from '../types'
import {
  autoBettingSignalKey,
  buildCrownAlertSignalQueue,
  buildAutoBettingExecutionPlan,
  executionOddsFloor,
  extractCrownAlertSignalCandidates,
  filterFreshCrownAlertSignals,
  isCompletedDuplicateAutoBettingReason,
  isNonRetriableAutoBettingReason,
  selectNextCrownAlertSignal,
  shouldCompleteCrownSignalForAccounts,
  type AutoBettingMode,
  type AutoBettingExecutionPlanRow,
  type AutoBettingSignal,
  type OddsAlertRecord,
} from '../pages/crownBettingExecutionPlan'
import {
  acquireCrownBettingAutomationLock,
  releaseCrownBettingAutomationLock,
} from '../pages/crownBettingAutomationLock'

const ACCOUNTS_STORAGE_KEY = 'crown-betting-accounts'
const SETTINGS_STORAGE_KEY = 'crown-betting-automation-settings'
const POLL_INTERVAL_MS = 5000
const SIGNAL_RETRY_COOLDOWN_MS = POLL_INTERVAL_MS
const ACCOUNT_EXECUTION_TIMEOUT_MS = 30000
const CROWN_LOGIN_URL = 'https://m407.mos077.com/'

type StoredAutomationSettings = {
  autoMode: AutoBettingMode
  autoEnabled: boolean
  perAccountLimit: number
  betLimit: number
  minimumBetOdds: number
  signalMaxAgeSeconds: number
}

type StoredCrownAccount = {
  id: string
  displayName: string
  loginName: string
  loginUrl?: string
  adsPowerProfileId?: string
  adsPowerStatus?: 'unlinked' | 'starting' | 'opened' | 'closed' | 'error'
  bettingEnabled?: boolean
  status: 'unchecked' | 'checking' | 'success' | 'error'
  balance?: number | null
  currency?: string
}

type NotificationConfigResponse = {
  enabled?: boolean
  config?: {
    data?: {
      monitorModeEnabled?: boolean
      liveOnlyModeEnabled?: boolean
    }
    monitorModeEnabled?: boolean
    liveOnlyModeEnabled?: boolean
  }
}

type SystemConfigResponse = {
  autoBettingEnabled?: boolean
}

type AutoBettingDecisionResponse = {
  id?: number
  status: string
  reason?: string
  crownHistoryVerified?: boolean
  crownBetReference?: string | null
}

type CrownSessionResponse = {
  profileId?: string
  opened: boolean
  loggedIn: boolean
  accountStatus?: string
  balance?: number
  currency?: string
}

const unreadableOpenedCrownSessionStatuses = new Set([
  'crown_page_not_found',
  'no_logged_in_crown_profile',
  'unknown',
  'browser_debug_port_missing',
])

const shouldKeepKnownOnlineCrownSession = (
  account: StoredCrownAccount,
  result: CrownSessionResponse,
) => (
  result.opened &&
  !result.loggedIn &&
  account.status === 'success' &&
  unreadableOpenedCrownSessionStatuses.has(result.accountStatus || '')
)

const normalizeNumberSetting = (
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

const readStoredAutomationSettings = (): StoredAutomationSettings => {
  try {
    const raw = window.localStorage.getItem(SETTINGS_STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) as Partial<StoredAutomationSettings> : {}
    return {
      autoMode: parsed.autoMode === 'live' ? 'live' : 'prematch',
      autoEnabled: parsed.autoEnabled === true,
      perAccountLimit: normalizeNumberSetting(parsed.perAccountLimit, 50, 50, 500),
      betLimit: normalizeNumberSetting(parsed.betLimit, 100, 10),
      minimumBetOdds: normalizeNumberSetting(parsed.minimumBetOdds, 0.70, 0.01),
      signalMaxAgeSeconds: normalizeNumberSetting(parsed.signalMaxAgeSeconds, 360, 1, 3600),
    }
  } catch {
    return {
      autoMode: 'prematch',
      autoEnabled: false,
      perAccountLimit: 50,
      betLimit: 100,
      minimumBetOdds: 0.70,
      signalMaxAgeSeconds: 360,
    }
  }
}

const writeStoredAutomationSettings = (settings: StoredAutomationSettings) => {
  window.localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(settings))
}

const readStoredAccounts = (): StoredCrownAccount[] => {
  try {
    const raw = window.localStorage.getItem(ACCOUNTS_STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) as StoredCrownAccount[] : []
    return Array.isArray(parsed)
      ? parsed.filter((account) => account?.id && account.displayName && account.loginName)
      : []
  } catch {
    return []
  }
}

const monitorModeFromNotificationConfigs = (configs: NotificationConfigResponse[]): AutoBettingMode | null => {
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

const readMonitorMode = async (fallbackMode: AutoBettingMode): Promise<AutoBettingMode> => {
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

const readBackendAutoBettingEnabled = async (): Promise<boolean> => {
  try {
    const response = await apiClient.post<ApiResponse<SystemConfigResponse>>('/system/config/get', {})
    return response.data.code === 0 && response.data.data?.autoBettingEnabled === true
  } catch {
    return false
  }
}

const verifyAccountBeforeBetting = async (account: StoredCrownAccount): Promise<StoredCrownAccount> => {
  if (account.bettingEnabled !== true) {
    return account
  }
  try {
    const response = await apiClient.post<ApiResponse<CrownSessionResponse>>(
      '/auto-betting/adspower/crown-session/match',
      {
        loginName: account.loginName,
        loginUrl: account.loginUrl || CROWN_LOGIN_URL,
        preferredProfileId: account.adsPowerProfileId?.trim() || undefined,
      },
    )
    const result = response.data.data
    if (response.data.code !== 0 || !result) {
      return { ...account, status: 'error', adsPowerStatus: 'error', balance: null }
    }
    const isClosed = !result.opened && result.accountStatus === 'profile_closed'
    const keepKnownOnline = shouldKeepKnownOnlineCrownSession(account, result)
    const loggedInOrKnownOnline = result.loggedIn || keepKnownOnline
    return {
      ...account,
      status: loggedInOrKnownOnline ? 'success' : (isClosed ? 'unchecked' : 'error'),
      adsPowerStatus: result.opened ? 'opened' : (isClosed ? 'closed' : 'error'),
      adsPowerProfileId: loggedInOrKnownOnline && result.profileId ? result.profileId : account.adsPowerProfileId,
      balance: typeof result.balance === 'number' ? result.balance : (keepKnownOnline ? account.balance : null),
      currency: result.currency || account.currency,
    }
  } catch {
    return { ...account, status: 'error', adsPowerStatus: 'error', balance: null }
  }
}

const toExecutionAccounts = (accounts: StoredCrownAccount[]) => accounts.map((account) => ({
  id: account.id,
  displayName: account.displayName,
  status: account.status,
  adsPowerProfileId: account.adsPowerProfileId,
  adsPowerStatus: account.adsPowerStatus,
  bettingEnabled: account.bettingEnabled === true,
}))

const runAutomationForSignal = async (
  signal: AutoBettingSignal,
  settings: StoredAutomationSettings,
  accounts: StoredCrownAccount[],
  queuePosition?: number,
  queueTotal?: number,
): Promise<{ succeeded: boolean; stopRetry: boolean; completed: boolean }> => {
  const checkedAccountById = new Map<string, StoredCrownAccount>()
  const accountsForPlan = () => accounts.map((account) => checkedAccountById.get(account.id) || account)
  const buildPlan = () => buildAutoBettingExecutionPlan({
    signal,
    mode: settings.autoMode,
    enabled: settings.autoEnabled,
    perAccountLimit: settings.perAccountLimit,
    betLimit: settings.betLimit,
    minimumBetOdds: settings.minimumBetOdds,
    accounts: toExecutionAccounts(accountsForPlan()),
  })

  const executeRow = async (row: AutoBettingExecutionPlanRow, checkedAccount: StoredCrownAccount) => {
    if (row.status !== 'passed' || row.stakeAmount <= 0) return { row, succeeded: false, stopRetry: false }
    const profileId = checkedAccount?.adsPowerProfileId?.trim()
    if (!profileId) return { row, succeeded: false, stopRetry: false }
    try {
      const signalResponse = await apiClient.post<ApiResponse<AutoBettingDecisionResponse>>(
        '/auto-betting/signals/odds-monitor',
        {
          signalSource: 'odds_monitor',
          accountKey: row.id,
          accountDisplayName: row.accountName,
          bettingMode: row.bettingMode,
          matchPhase: row.matchPhase,
          leagueName: row.leagueName,
          matchTitle: row.matchTitle,
          marketType: row.marketType,
          lineValue: row.lineValue,
          selectionName: row.selectionName,
          referenceSourceKey: row.referenceSourceKey,
          targetSourceKey: row.targetSourceKey,
          referenceOdds: row.referenceOdds,
          targetOdds: row.targetOdds,
          minimumTargetOdds: settings.minimumBetOdds,
          queuePosition,
          queueTotal,
          oddsChangeDirection: row.oddsChangeDirection,
          stakeAmount: row.stakeAmount,
          accountStakeLimit: settings.betLimit,
          capturedAt: row.capturedAt,
          maxSignalAgeSeconds: settings.signalMaxAgeSeconds,
        },
      )
      const decision = signalResponse.data.data
      if (signalResponse.data.code !== 0 || decision?.status !== 'ready' || typeof decision.id !== 'number') {
        const completedDuplicate = isCompletedDuplicateAutoBettingReason(decision?.reason)
        return {
          row: {
            ...row,
            status: 'skipped' as const,
            statusLabel: completedDuplicate ? '已下注' : '跳过',
            stakeAmount: 0,
            stopRetry: isNonRetriableAutoBettingReason(decision?.reason),
            retryable: completedDuplicate ? false : undefined,
            reason: decision?.reason || '后端投注判断失败',
          },
          succeeded: false,
          stopRetry: isNonRetriableAutoBettingReason(decision?.reason),
        }
      }
      const executionResponse = await apiClient.post<ApiResponse<AutoBettingDecisionResponse>>(
        `/auto-betting/intents/${decision.id}/execute-crown`,
        {
          profileId,
          loginUrl: checkedAccount?.loginUrl || CROWN_LOGIN_URL,
          minimumTargetOdds: executionOddsFloor(row.targetOdds, settings.minimumBetOdds),
        },
        { timeout: ACCOUNT_EXECUTION_TIMEOUT_MS },
      )
      const executionDecision = executionResponse.data.data
      const succeeded = executionResponse.data.code === 0 &&
        executionDecision?.status === 'placed' &&
        executionDecision.crownHistoryVerified === true
      return {
        row: {
          ...row,
          status: succeeded ? 'passed' as const : 'skipped' as const,
          statusLabel: succeeded ? '已下注' : '跳过',
          stakeAmount: succeeded ? row.stakeAmount : 0,
          stopRetry: !succeeded && isNonRetriableAutoBettingReason(executionDecision?.reason),
          reason: executionDecision?.reason || '皇冠实际下注失败',
        },
        succeeded,
        stopRetry: !succeeded && isNonRetriableAutoBettingReason(executionDecision?.reason),
      }
    } catch {
      return {
        row: {
          ...row,
          status: 'skipped' as const,
          statusLabel: '跳过',
          stakeAmount: 0,
          reason: '皇冠实际下注失败',
        },
        succeeded: false,
        stopRetry: false,
      }
    }
  }
  const results: Array<{ row: AutoBettingExecutionPlanRow; succeeded: boolean; stopRetry: boolean }> = []
  for (const account of accounts.filter((item) => item.bettingEnabled === true)) {
    const checkedAccount = await verifyAccountBeforeBetting(account)
    checkedAccountById.set(checkedAccount.id, checkedAccount)
    const row = buildPlan().rows.find((candidate) => candidate.id === checkedAccount.id)
    if (row) {
      results.push(await executeRow(row, checkedAccount))
    }
  }
  const targetAccountIds = accounts.filter((item) => item.bettingEnabled === true).map((account) => account.id)
  return {
    succeeded: results.some((result) => result.succeeded),
    stopRetry: results.some((result) => result.stopRetry),
    completed: results.length === targetAccountIds.length &&
      shouldCompleteCrownSignalForAccounts(results.map((result) => result.row), targetAccountIds),
  }
}

const CrownBettingBackgroundRunner = () => {
  const runningRef = useRef(false)
  const attemptedSignalAtRef = useRef<Map<string, number>>(new Map())
  const completedSignalKeysRef = useRef<Set<string>>(new Set())

  useEffect(() => {
    let disposed = false
    const runOnce = async () => {
      if (disposed || runningRef.current) return
      const storedSettings = readStoredAutomationSettings()
      if (!storedSettings.autoEnabled) return
      const backendAutoBettingEnabled = await readBackendAutoBettingEnabled()
      if (!backendAutoBettingEnabled) {
        writeStoredAutomationSettings({ ...storedSettings, autoEnabled: false })
        return
      }

      const owner = `background-${Date.now()}-${Math.random()}`
      if (!acquireCrownBettingAutomationLock(owner)) return
      runningRef.current = true
      try {
        const activeMode = await readMonitorMode(storedSettings.autoMode)
        const settings = { ...storedSettings, autoMode: activeMode }
        if (activeMode !== storedSettings.autoMode) {
          writeStoredAutomationSettings(settings)
        }
        const accounts = readStoredAccounts()
        if (accounts.length === 0) return
        const alertResponse = await apiClient.post<ApiResponse<OddsAlertRecord[]>>(
          '/odds-monitor/alerts/list',
          {},
        )
        if (alertResponse.data.code !== 0 || !Array.isArray(alertResponse.data.data)) {
          return
        }
        const now = Date.now()
        const candidates = filterFreshCrownAlertSignals(
          extractCrownAlertSignalCandidates(alertResponse.data.data, settings.autoMode),
          now,
          settings.signalMaxAgeSeconds,
        ).filter((candidate) => candidate.targetOdds >= settings.minimumBetOdds)
        const signalSelectionOptions = {
          completedSignalKeys: completedSignalKeysRef.current,
          attemptedSignalAt: attemptedSignalAtRef.current,
          now,
          retryCooldownMs: SIGNAL_RETRY_COOLDOWN_MS,
        }
        const signalQueue = buildCrownAlertSignalQueue(candidates, signalSelectionOptions)
        const signal = selectNextCrownAlertSignal(candidates, signalSelectionOptions)
        if (!signal) return
        const selectedQueueItem = signalQueue.find((candidate) => (
          autoBettingSignalKey(candidate) === autoBettingSignalKey(signal)
        ))

        const key = autoBettingSignalKey(signal)
        attemptedSignalAtRef.current.set(key, now)
        const result = await runAutomationForSignal(
          signal,
          settings,
          accounts,
          selectedQueueItem?.queuePosition,
          signalQueue.length,
        )
        if (result.completed || result.stopRetry) {
          completedSignalKeysRef.current.add(key)
        }
      } finally {
        runningRef.current = false
        releaseCrownBettingAutomationLock(owner)
      }
    }

    void runOnce()
    const intervalId = window.setInterval(() => {
      void runOnce()
    }, POLL_INTERVAL_MS)
    return () => {
      disposed = true
      window.clearInterval(intervalId)
    }
  }, [])

  return null
}

export default CrownBettingBackgroundRunner
