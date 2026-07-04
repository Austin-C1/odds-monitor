import { useCallback, useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import { apiClient } from '../../services/api'
import { extractApiErrorMessage } from '../../utils/apiError'
import {
  autoBettingSignalKey,
  buildAutoBettingExecutionPlan,
  buildCrownAlertSignalQueue,
  extractCrownAlertSignalCandidates,
  filterFreshCrownAlertSignals,
  formatAutoBettingReason,
  isCompletedDuplicateAutoBettingReason,
  selectNextCrownAlertSignal,
  shouldCompleteCrownSignalForAccounts,
  type AutoBettingExecutionPlan,
  type OddsAlertRecord,
  type QueuedCrownAlertSignal,
} from '../crownBettingExecutionPlan'
import {
  acquireCrownBettingAutomationLock,
  releaseCrownBettingAutomationLock,
} from '../crownBettingAutomationLock'
import { isBettingEnabledAccount, resolveCrownLoginUrl, toExecutionAccounts } from '../crownBettingAccounts'
import {
  AUTO_BETTING_ACCOUNT_EXECUTION_TIMEOUT_MS,
  AUTO_BETTING_POLL_INTERVAL_MS,
  persistAutomationSettings,
  readMonitorMode,
  readStoredAutomationSettings,
  updateAutoBettingEnabled,
} from '../crownBettingSettings'
import type {
  ApiResponse,
  AutoBettingDecisionResponse,
  AutomationSettings,
  CrownAccount,
  CurrentExecutionStep,
} from '../crownBettingTypes'

type UseCrownAutomationOptions = {
  accountsRef: React.MutableRefObject<CrownAccount[]>
  enabledBettingAccounts: CrownAccount[]
}

export const useCrownAutomation = ({
  accountsRef,
  enabledBettingAccounts,
}: UseCrownAutomationOptions) => {
  const [automationSettings, setAutomationSettings] = useState<AutomationSettings>(readStoredAutomationSettings)
  const [executionPlan, setExecutionPlan] = useState<AutoBettingExecutionPlan | null>(null)
  const [signalCandidates, setSignalCandidates] = useState<QueuedCrownAlertSignal[]>([])
  const [executionRunning, setExecutionRunning] = useState(false)
  const [currentExecutionStep, setCurrentExecutionStep] = useState<CurrentExecutionStep>(null)
  const executionRunningRef = useRef(false)
  const automationPollingRef = useRef(false)
  const completedSignalKeysRef = useRef<Set<string>>(new Set())
  const mountedRef = useRef(true)

  const {
    autoMode,
    autoEnabled,
    perAccountLimit,
    betLimit,
    minimumBetOdds,
    signalMaxAgeSeconds,
  } = automationSettings

  const syncAutoBettingEnabled = async (enabled: boolean) => {
    try {
      const response = await updateAutoBettingEnabled(enabled)
      if (response.data.code !== 0) {
        throw new Error(response.data.msg || '自动投注后端开关同步失败')
      }
    } catch (error: any) {
      if (enabled) {
        const disabledSettings = { ...readStoredAutomationSettings(), autoEnabled: false }
        persistAutomationSettings(disabledSettings)
        setAutomationSettings(disabledSettings)
      }
      message.error(extractApiErrorMessage(error, enabled ? '自动投注开启失败' : '自动投注关闭失败'))
    }
  }

  const updateAutomationSetting = <Key extends keyof AutomationSettings>(
    key: Key,
    value: AutomationSettings[Key],
  ) => {
    const nextSettings = {
      ...automationSettings,
      [key]: value,
    }
    setAutomationSettings(nextSettings)
    persistAutomationSettings(nextSettings)
    if (key === 'autoEnabled') {
      void syncAutoBettingEnabled(Boolean(value))
    }
  }

  const saveAutomationSettings = () => {
    persistAutomationSettings(automationSettings)
    message.success('投注设置已保存')
  }

  useEffect(() => {
    let cancelled = false
    const syncAutoModeFromMonitorConfig = async () => {
      try {
        const monitorMode = await readMonitorMode(readStoredAutomationSettings().autoMode)
        if (cancelled) return
        setAutomationSettings((currentSettings) => {
          if (currentSettings.autoMode === monitorMode) return currentSettings
          const nextSettings = { ...currentSettings, autoMode: monitorMode }
          persistAutomationSettings(nextSettings)
          return nextSettings
        })
      } catch {
        // Keep the local betting mode when monitor settings cannot be read.
      }
    }
    void syncAutoModeFromMonitorConfig()
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    void updateAutoBettingEnabled(readStoredAutomationSettings().autoEnabled).catch(() => undefined)
  }, [])

  useEffect(() => () => {
    mountedRef.current = false
  }, [])

  const executeLatestCrownSignal = useCallback(async () => {
    const currentAccounts = accountsRef.current
    if (automationPollingRef.current || executionRunningRef.current) {
      return
    }
    automationPollingRef.current = true
    const lockOwner = `page-${Date.now()}-${Math.random()}`
    if (!acquireCrownBettingAutomationLock(lockOwner)) {
      automationPollingRef.current = false
      return
    }
    let executionStarted = false
    try {
      const alertResponse = await apiClient.post<ApiResponse<OddsAlertRecord[]>>(
        '/odds-monitor/alerts/list',
        {},
      )
      if (!mountedRef.current) return
      if (alertResponse.data.code !== 0) {
        throw new Error(alertResponse.data.msg || '监控信号读取失败')
      }
      const now = Date.now()
      const candidates = filterFreshCrownAlertSignals(
        extractCrownAlertSignalCandidates(alertResponse.data.data, autoMode),
        now,
        signalMaxAgeSeconds,
      )
      const qualifiedCandidates = candidates.filter((candidate) => (
        candidate.targetOdds >= minimumBetOdds
      ))
      const signalSelectionOptions = {
        completedSignalKeys: completedSignalKeysRef.current,
        now,
      }
      const signalQueue = buildCrownAlertSignalQueue(qualifiedCandidates, signalSelectionOptions)
      setSignalCandidates(signalQueue)
      const signal = selectNextCrownAlertSignal(qualifiedCandidates, signalSelectionOptions)
      const selectedQueueItem = signal
        ? signalQueue.find((candidate) => autoBettingSignalKey(candidate) === autoBettingSignalKey(signal))
        : null
      const signalKey = signal ? autoBettingSignalKey(signal) : null
      const shouldExecuteSignal = autoEnabled && Boolean(signal)
      if (shouldExecuteSignal) {
        executionStarted = true
        executionRunningRef.current = true
        setExecutionRunning(true)
        setCurrentExecutionStep({
          accountName: '-',
          stageLabel: '读取信号',
          phaseLabel: signal?.modeLabel || (autoMode === 'prematch' ? '赛前' : '滚球'),
          signalTitle: signal?.matchTitle || '等待监控信号',
          queueLabel: selectedQueueItem ? `#${selectedQueueItem.queuePosition}/${signalQueue.length}` : '-',
        })
      }
      const buildExecutionPlan = (planAccounts: CrownAccount[]) => buildAutoBettingExecutionPlan({
        signal,
        mode: autoMode,
        enabled: autoEnabled,
        perAccountLimit,
        betLimit,
        minimumBetOdds,
        accounts: toExecutionAccounts(planAccounts),
      })
      const nextPlan = buildExecutionPlan(currentAccounts)
      setExecutionPlan((currentPlan) => (
        signal || !currentPlan?.signal ? nextPlan : currentPlan
      ))
      if (!shouldExecuteSignal) {
        return
      }
      const executionAccounts = currentAccounts.filter(isBettingEnabledAccount)
      if (executionAccounts.length === 0) {
        setCurrentExecutionStep((current) => current ? { ...current, stageLabel: nextPlan.summary } : null)
        return
      }
      const activeSignal = signal
      if (!activeSignal) return

      const executableRows = nextPlan.rows.filter((row) => row.status === 'passed' && row.stakeAmount > 0)
      if (executableRows.length === 0) {
        setCurrentExecutionStep((current) => current ? { ...current, stageLabel: nextPlan.summary } : null)
        return
      }
      const accountById = new Map(currentAccounts.map((account) => [account.id, account]))
      const queueTimeout = Math.max(
        AUTO_BETTING_ACCOUNT_EXECUTION_TIMEOUT_MS,
        executableRows.length * AUTO_BETTING_ACCOUNT_EXECUTION_TIMEOUT_MS + 5000,
      )
      const queueFailureRows = (reason: string) => nextPlan.rows.map((row) => (
        executableRows.some((candidate) => candidate.id === row.id)
          ? {
              ...row,
              status: 'skipped' as const,
              statusLabel: '跳过',
              stakeAmount: 0,
              stopRetry: false,
              retryable: true,
              reason,
            }
          : row
      ))
      setCurrentExecutionStep({
        accountName: `${executableRows.length} 个账号`,
        stageLabel: '后端队列执行',
        phaseLabel: activeSignal.modeLabel,
        signalTitle: activeSignal.matchTitle,
        queueLabel: selectedQueueItem ? `#${selectedQueueItem.queuePosition}/${signalQueue.length}` : '-',
      })
      let queueDecisions: AutoBettingDecisionResponse[] = []
      try {
        const queueResponse = await apiClient.post<ApiResponse<AutoBettingDecisionResponse[]>>(
          '/auto-betting/signals/odds-monitor/execute-crown-queue',
          {
            signalSource: 'odds_monitor',
            bettingMode: activeSignal.bettingMode,
            matchPhase: activeSignal.matchPhase,
            leagueName: activeSignal.leagueName,
            matchTitle: activeSignal.matchTitle,
            marketType: activeSignal.marketType,
            lineValue: activeSignal.lineValue,
            selectionName: activeSignal.selectionName,
            referenceSourceKey: activeSignal.referenceSourceKey,
            targetSourceKey: activeSignal.targetSourceKey,
            referenceOdds: activeSignal.referenceOdds,
            targetOdds: activeSignal.targetOdds,
            minimumTargetOdds: minimumBetOdds,
            queuePosition: selectedQueueItem?.queuePosition,
            queueTotal: signalQueue.length,
            oddsChangeDirection: activeSignal.oddsChangeDirection,
            stakeAmount: executableRows[0].stakeAmount,
            accountStakeLimit: betLimit,
            capturedAt: activeSignal.sourceAlertCreatedAt || Date.now(),
            maxSignalAgeSeconds: signalMaxAgeSeconds,
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
          { timeout: queueTimeout },
        )
        if (!mountedRef.current) return
        if (queueResponse.data.code !== 0 || !Array.isArray(queueResponse.data.data)) {
          throw new Error(queueResponse.data.msg || '后端投注队列执行失败')
        }
        queueDecisions = queueResponse.data.data
      } catch (error: any) {
        const reason = extractApiErrorMessage(error, '后端投注队列执行失败')
        const finalRows = queueFailureRows(reason)
        setExecutionPlan({
          ...nextPlan,
          rows: finalRows,
          canExecute: false,
          totalStake: 0,
          availableAccountCount: 0,
          summary: '没有确认成功的下注',
        })
        message.warning('本轮信号执行失败，后续可重新尝试')
        setCurrentExecutionStep((current) => current ? { ...current, stageLabel: '本轮完成' } : null)
        return
      }

      const decisionByAccountKey = new Map(queueDecisions.map((decision) => [decision.accountKey, decision]))
      const finalRows = nextPlan.rows.map((row) => {
        const decision = decisionByAccountKey.get(row.id)
        if (!decision) return row
        const historyVerified = decision.status === 'placed' && decision.crownHistoryVerified === true
        const completedDuplicate = isCompletedDuplicateAutoBettingReason(decision.reason)
        const completed = historyVerified || completedDuplicate
        return {
          ...row,
          status: historyVerified ? 'passed' as const : 'skipped' as const,
          statusLabel: completed ? '已下注' : '跳过',
          stakeAmount: historyVerified ? row.stakeAmount : 0,
          stopRetry: completed,
          retryable: !completed,
          reason: decision.crownBetReference
            ? `${formatAutoBettingReason(decision.reason)} ${decision.crownBetReference}`
            : formatAutoBettingReason(decision.reason),
        }
      })
      const placedRows = finalRows.filter((row) => row.status === 'passed')
      const placedStake = placedRows.reduce((total, row) => total + row.stakeAmount, 0)
      setExecutionPlan({
        ...nextPlan,
        rows: finalRows,
        canExecute: placedRows.length > 0,
        totalStake: placedStake,
        availableAccountCount: placedRows.length,
        summary: placedRows.length > 0
          ? `下注成功 ${placedRows.length} 个账号`
          : '没有确认成功的下注',
      })
      if (signalKey && shouldCompleteCrownSignalForAccounts(finalRows, executableRows.map((row) => row.id))) {
        completedSignalKeysRef.current.add(signalKey)
      }
      if (placedRows.length > 0) {
        message.success('下注队列已完成，成功记录已写入后端')
      } else {
        message.warning('本轮信号没有确认成功，后续可重新尝试')
      }
      setCurrentExecutionStep((current) => current ? { ...current, stageLabel: '本轮完成' } : null)
    } catch (error: any) {
      message.error(extractApiErrorMessage(error, '监控信号读取失败'))
    } finally {
      releaseCrownBettingAutomationLock(lockOwner)
      automationPollingRef.current = false
      if (executionStarted) {
        executionRunningRef.current = false
        setExecutionRunning(false)
      }
    }
  }, [
    accountsRef,
    autoEnabled,
    autoMode,
    betLimit,
    minimumBetOdds,
    perAccountLimit,
    signalMaxAgeSeconds,
  ])

  useEffect(() => {
    void executeLatestCrownSignal()
    const intervalId = window.setInterval(() => {
      void executeLatestCrownSignal()
    }, AUTO_BETTING_POLL_INTERVAL_MS)
    return () => window.clearInterval(intervalId)
  }, [autoEnabled, enabledBettingAccounts.length, executeLatestCrownSignal])

  const currentExecutionSignal = executionPlan?.signal
  const currentExecutionSignalQueueItem = currentExecutionSignal
    ? signalCandidates.find((candidate) => autoBettingSignalKey(candidate) === autoBettingSignalKey(currentExecutionSignal))
    : null
  const currentExecutionSignalLabel = executionRunning
    ? '正在投注盘口'
    : currentExecutionSignal ? '最近处理盘口' : '等待合格信号'
  const hasExecutionAttemptResult = executionPlan
    ? executionPlan.summary.startsWith('下注成功') || executionPlan.summary === '没有确认成功的下注'
    : false
  const executionAccountTagLabel = executionPlan
    ? `${hasExecutionAttemptResult ? '已下注账号' : '可投账号'} ${executionPlan.availableAccountCount} 个`
    : ''
  const executionAccountTagColor = hasExecutionAttemptResult
    ? executionPlan && executionPlan.availableAccountCount > 0 ? 'success' : 'default'
    : 'blue'

  return {
    automationSettings,
    updateAutomationSetting,
    saveAutomationSettings,
    executionPlan,
    signalCandidates,
    executionRunning,
    currentExecutionStep,
    currentExecutionSignal,
    currentExecutionSignalQueueItem,
    currentExecutionSignalLabel,
    executionAccountTagLabel,
    executionAccountTagColor,
  }
}
