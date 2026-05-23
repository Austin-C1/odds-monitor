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
  isNonRetriableAutoBettingReason,
  selectNextCrownAlertSignal,
  type AutoBettingMode,
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
      signalMaxAgeSeconds: normalizeNumberSetting(parsed.signalMaxAgeSeconds, 600, 1, 3600),
    }
  } catch {
    return {
      autoMode: 'prematch',
      autoEnabled: false,
      perAccountLimit: 50,
      betLimit: 100,
      minimumBetOdds: 0.70,
      signalMaxAgeSeconds: 600,
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

const runAutomationForSignal = async (
  signal: AutoBettingSignal,
  settings: StoredAutomationSettings,
  accounts: StoredCrownAccount[],
  queuePosition?: number,
  queueTotal?: number,
): Promise<{ succeeded: boolean; stopRetry: boolean }> => {
  const checkedAccounts: StoredCrownAccount[] = []
  for (const account of accounts) {
    checkedAccounts.push(await verifyAccountBeforeBetting(account))
  }
  const checkedAccountById = new Map(checkedAccounts.map((account) => [account.id, account]))
  const plan = buildAutoBettingExecutionPlan({
    signal,
    mode: settings.autoMode,
    enabled: settings.autoEnabled,
    perAccountLimit: settings.perAccountLimit,
    betLimit: settings.betLimit,
    minimumBetOdds: settings.minimumBetOdds,
    accounts: checkedAccounts.map((account) => ({
      id: account.id,
      displayName: account.displayName,
      status: account.status,
      adsPowerProfileId: account.adsPowerProfileId,
      adsPowerStatus: account.adsPowerStatus,
      bettingEnabled: account.bettingEnabled === true,
    })),
  })
  if (!plan.canExecute) {
    return { succeeded: false, stopRetry: false }
  }

  const executeRow = async (row: (typeof plan.rows)[number]) => {
    if (row.status !== 'passed' || row.stakeAmount <= 0) return { succeeded: false, stopRetry: false }
    const checkedAccount = checkedAccountById.get(row.id)
    const profileId = checkedAccount?.adsPowerProfileId?.trim()
    if (!profileId) return { succeeded: false, stopRetry: false }
    const signalResponse = await apiClient.post<ApiResponse<AutoBettingDecisionResponse>>(
      '/auto-betting/signals/odds-monitor',
      {
        signalSource: 'odds_monitor',
        accountKey: row.id,
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
        capturedAt: row.capturedAt,
        maxSignalAgeSeconds: settings.signalMaxAgeSeconds,
      },
    )
    const decision = signalResponse.data.data
    if (signalResponse.data.code !== 0 || decision?.status !== 'ready' || typeof decision.id !== 'number') {
      return { succeeded: false, stopRetry: isNonRetriableAutoBettingReason(decision?.reason) }
    }
    const executionResponse = await apiClient.post<ApiResponse<AutoBettingDecisionResponse>>(
      `/auto-betting/intents/${decision.id}/execute-crown`,
      {
        profileId,
        loginUrl: checkedAccount?.loginUrl || CROWN_LOGIN_URL,
        minimumTargetOdds: executionOddsFloor(row.targetOdds, settings.minimumBetOdds),
      },
      { timeout: 120000 },
    )
    const executionDecision = executionResponse.data.data
    const succeeded = executionResponse.data.code === 0 &&
      executionDecision?.status === 'placed' &&
      executionDecision.crownHistoryVerified === true
    return {
      succeeded,
      stopRetry: !succeeded && isNonRetriableAutoBettingReason(executionDecision?.reason),
    }
  }
  const results: Array<{ succeeded: boolean; stopRetry: boolean }> = []
  for (const row of plan.rows) {
    results.push(await executeRow(row))
  }
  return {
    succeeded: results.some((result) => result.succeeded),
    stopRetry: results.some((result) => result.stopRetry),
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
        if (result.succeeded || result.stopRetry) {
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
