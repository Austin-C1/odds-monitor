import { useEffect, useRef } from 'react'
import { apiClient } from '../services/api'
import {
  autoBettingSignalKey,
  buildCrownAlertSignalQueue,
  buildAutoBettingExecutionPlan,
  extractCrownAlertSignalCandidates,
  filterFreshCrownAlertSignals,
  isCompletedDuplicateAutoBettingReason,
  selectNextCrownAlertSignal,
  shouldCompleteCrownSignalForAccounts,
  type AutoBettingSignal,
  type OddsAlertRecord,
} from '../pages/crownBettingExecutionPlan'
import {
  acquireCrownBettingAutomationLock,
  releaseCrownBettingAutomationLock,
} from '../pages/crownBettingAutomationLock'
import {
  readStoredAccounts,
  resolveCrownLoginUrl,
  toExecutionAccounts,
} from '../pages/crownBettingAccounts'
import {
  AUTO_BETTING_ACCOUNT_EXECUTION_TIMEOUT_MS as ACCOUNT_EXECUTION_TIMEOUT_MS,
  AUTO_BETTING_POLL_INTERVAL_MS as POLL_INTERVAL_MS,
  persistAutomationSettings as writeStoredAutomationSettings,
  readBackendAutoBettingEnabled,
  readMonitorMode,
  readStoredAutomationSettings,
} from '../pages/crownBettingSettings'
import type {
  ApiResponse,
  AutoBettingDecisionResponse,
  AutomationSettings,
  CrownAccount,
} from '../pages/crownBettingTypes'

const runQueuedAutomationForSignal = async (
  signal: AutoBettingSignal,
  settings: AutomationSettings,
  accounts: CrownAccount[],
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
  settings: AutomationSettings,
  accounts: CrownAccount[],
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
