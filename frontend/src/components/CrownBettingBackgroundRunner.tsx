import { useEffect, useRef } from 'react'
import { apiClient } from '../services/api'
import type { ApiResponse } from '../types'
import {
  autoBettingSignalKey,
  buildCrownAlertSignalQueue,
  buildAutoBettingExecutionPlan,
  extractCrownAlertSignalCandidates,
  filterFreshCrownAlertSignals,
  isCompletedDuplicateAutoBettingReason,
  selectNextCrownAlertSignal,
  shouldCompleteCrownSignalForAccounts,
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
const ACCOUNT_EXECUTION_TIMEOUT_MS = 30000
const DEFAULT_CROWN_LOGIN_URL = (import.meta.env.VITE_CROWN_LOGIN_URL || '').trim()

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
  accountKey?: string
}

const resolveCrownLoginUrl = (loginUrl?: string | null) => loginUrl?.trim() || DEFAULT_CROWN_LOGIN_URL

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

const toExecutionAccounts = (accounts: StoredCrownAccount[]) => accounts.map((account) => ({
  id: account.id,
  displayName: account.displayName,
  status: account.status,
  adsPowerProfileId: account.adsPowerProfileId,
  adsPowerStatus: account.adsPowerStatus,
  bettingEnabled: account.bettingEnabled === true,
}))

const runQueuedAutomationForSignal = async (
  signal: AutoBettingSignal,
  settings: StoredAutomationSettings,
  accounts: StoredCrownAccount[],
  queuePosition?: number,
  queueTotal?: number,
): Promise<{ succeeded: boolean; stopRetry: boolean; completed: boolean }> => {
  const plan = buildAutoBettingExecutionPlan({
    signal,
    mode: settings.autoMode,
    enabled: settings.autoEnabled,
    perAccountLimit: settings.perAccountLimit,
    betLimit: settings.betLimit,
    minimumBetOdds: settings.minimumBetOdds,
    accounts: toExecutionAccounts(accounts),
  })
  const executableRows = plan.rows.filter((row) => row.status === 'passed' && row.stakeAmount > 0)
  if (executableRows.length === 0) {
    return { succeeded: false, stopRetry: false, completed: false }
  }

  const accountById = new Map(accounts.map((account) => [account.id, account]))
  try {
    const response = await apiClient.post<ApiResponse<AutoBettingDecisionResponse[]>>(
      '/auto-betting/signals/odds-monitor/execute-crown-queue',
      {
        signalSource: 'odds_monitor',
        bettingMode: signal.bettingMode,
        matchPhase: signal.matchPhase,
        leagueName: signal.leagueName,
        matchTitle: signal.matchTitle,
        marketType: signal.marketType,
        lineValue: signal.lineValue,
        selectionName: signal.selectionName,
        referenceSourceKey: signal.referenceSourceKey,
        targetSourceKey: signal.targetSourceKey,
        referenceOdds: signal.referenceOdds,
        targetOdds: signal.targetOdds,
        minimumTargetOdds: settings.minimumBetOdds,
        queuePosition,
        queueTotal,
        oddsChangeDirection: signal.oddsChangeDirection,
        stakeAmount: executableRows[0].stakeAmount,
        accountStakeLimit: settings.betLimit,
        capturedAt: signal.sourceAlertCreatedAt || Date.now(),
        maxSignalAgeSeconds: settings.signalMaxAgeSeconds,
        accounts: executableRows.map((row) => {
          const account = accountById.get(row.id)
          return {
            accountKey: row.id,
            accountDisplayName: row.accountName,
            profileId: account?.adsPowerProfileId?.trim() || '',
            loginUrl: resolveCrownLoginUrl(account?.loginUrl),
            bettingEnabled: true,
          }
        }),
      },
      { timeout: Math.max(ACCOUNT_EXECUTION_TIMEOUT_MS, executableRows.length * ACCOUNT_EXECUTION_TIMEOUT_MS + 5000) },
    )
    const decisions = response.data.data || []
    const decisionByAccountKey = new Map(decisions.map((decision) => [decision.accountKey, decision]))
    const finalRows = plan.rows.map((row) => {
      const decision = decisionByAccountKey.get(row.id)
      if (!decision) return row
      const verified = decision.status === 'placed' && decision.crownHistoryVerified === true
      const duplicatePlaced = isCompletedDuplicateAutoBettingReason(decision.reason)
      return {
        ...row,
        status: verified ? 'passed' as const : 'skipped' as const,
        stakeAmount: verified ? row.stakeAmount : 0,
        stopRetry: verified || duplicatePlaced,
        retryable: !(verified || duplicatePlaced),
      }
    })
    const completed = response.data.code === 0 && shouldCompleteCrownSignalForAccounts(
      finalRows,
      executableRows.map((row) => row.id),
    )
    return {
      succeeded: response.data.code === 0 && finalRows.some((row) => row.status === 'passed') && decisions.some((decision) => (
        decision.status === 'placed' && decision.crownHistoryVerified === true
      )),
      stopRetry: completed,
      completed,
    }
  } catch {
    return { succeeded: false, stopRetry: false, completed: false }
  }
}

const runAutomationForSignal = async (
  signal: AutoBettingSignal,
  settings: StoredAutomationSettings,
  accounts: StoredCrownAccount[],
  queuePosition?: number,
  queueTotal?: number,
): Promise<{ succeeded: boolean; stopRetry: boolean; completed: boolean }> => {
  return runQueuedAutomationForSignal(signal, settings, accounts, queuePosition, queueTotal)
}

const CrownBettingBackgroundRunner = () => {
  const runningRef = useRef(false)
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
          now,
        }
        const signalQueue = buildCrownAlertSignalQueue(candidates, signalSelectionOptions)
        const signal = selectNextCrownAlertSignal(candidates, signalSelectionOptions)
        if (!signal) return
        const selectedQueueItem = signalQueue.find((candidate) => (
          autoBettingSignalKey(candidate) === autoBettingSignalKey(signal)
        ))

        const key = autoBettingSignalKey(signal)
        const result = await runAutomationForSignal(
          signal,
          settings,
          accounts,
          selectedQueueItem?.queuePosition,
          signalQueue.length,
        )
        if (result.completed) {
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
