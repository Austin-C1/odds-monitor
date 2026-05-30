export type AutoBettingMode = 'prematch' | 'live'
export type ExecutionAccountStatus = 'unchecked' | 'checking' | 'success' | 'error'
export type ExecutionRowStatus = 'passed' | 'skipped'

export type ExecutionAccount = {
  id: string
  displayName: string
  status: ExecutionAccountStatus
  adsPowerProfileId?: string
  adsPowerStatus?: 'unlinked' | 'starting' | 'opened' | 'closed' | 'error'
  bettingEnabled?: boolean
}

export type OddsAlertRecord = {
  id: number
  title: string
  message: string
  createdAt: number
}

export type AutoBettingSignal = {
  sourceAlertId?: number
  sourceAlertCreatedAt?: number
  modeLabel: string
  bettingMode: AutoBettingMode
  matchPhase: AutoBettingMode
  leagueName: string
  matchTitle: string
  marketType: 'handicap' | 'total'
  marketTitle: string
  lineValue: string
  selectionName: string
  referenceSourceKey: 'crown'
  targetSourceKey: 'crown'
  referenceOdds: number
  targetOdds: number
  odds: number
  edge: number
  oddsChangeDirection?: 'rise' | 'drop'
  bettingLogic: string
}

export type AutoBettingExecutionPlanOptions = {
  signal: AutoBettingSignal | null
  mode: AutoBettingMode
  enabled: boolean
  perAccountLimit: number
  betLimit: number
  minimumBetOdds: number
  accounts: ExecutionAccount[]
}

export type AutoBettingExecutionPlanRow = {
  id: string
  accountName: string
  status: ExecutionRowStatus
  statusLabel: string
  modeLabel: string
  bettingMode: AutoBettingMode
  matchPhase: AutoBettingMode
  leagueName: string
  matchTitle: string
  marketType: 'handicap' | 'total'
  marketTitle: string
  lineValue: string
  selectionName: string
  referenceSourceKey: 'crown'
  targetSourceKey: 'crown'
  referenceOdds: number
  targetOdds: number
  odds: number
  edge: number
  oddsChangeDirection?: 'rise' | 'drop'
  bettingLogic: string
  capturedAt: number
  stakeAmount: number
  reason: string
  stopRetry?: boolean
  retryable?: boolean
}

export type AutoBettingExecutionPlan = {
  canExecute: boolean
  modeLabel: string
  summary: string
  totalStake: number
  availableAccountCount: number
  signal: AutoBettingSignal | null
  rows: AutoBettingExecutionPlanRow[]
}

const BACKEND_SINGLE_ACCOUNT_LIMIT = 500

const modeLabels: Record<AutoBettingMode, string> = {
  prematch: '赛前',
  live: '滚球',
}

export type CrownAlertSignalSelectionOptions = {
  completedSignalKeys: ReadonlySet<string>
  attemptedSignalAt: ReadonlyMap<string, number>
  now: number
  retryCooldownMs: number
}

export type QueuedCrownAlertSignal = AutoBettingSignal & {
  queuePosition: number
  queueStatus: 'ready' | 'cooldown'
}

export const autoBettingSignalKey = (signal: AutoBettingSignal) => [
  typeof signal.sourceAlertId === 'number' ? `alert:${signal.sourceAlertId}` : 'alert:none',
  signal.matchPhase,
  signal.matchTitle,
  signal.marketType,
  signal.lineValue || '',
  signal.selectionName,
  signal.referenceOdds,
  signal.targetOdds,
  signal.oddsChangeDirection || 'none',
].join('|')

export const buildCrownAlertSignalQueue = (
  candidates: AutoBettingSignal[],
  options: CrownAlertSignalSelectionOptions,
): QueuedCrownAlertSignal[] => {
  const pendingCandidates = candidates.filter((candidate) => (
    !options.completedSignalKeys.has(autoBettingSignalKey(candidate))
  ))
  const neverAttempted = pendingCandidates.filter((candidate) => (
    !options.attemptedSignalAt.has(autoBettingSignalKey(candidate))
  ))

  const retryCooldownMs = Math.max(0, options.retryCooldownMs)
  const attempted = pendingCandidates.filter((candidate) => (
    options.attemptedSignalAt.has(autoBettingSignalKey(candidate))
  ))
  const retryReady = attempted.filter((candidate) => {
    const lastAttemptAt = options.attemptedSignalAt.get(autoBettingSignalKey(candidate)) || 0
    return options.now - lastAttemptAt >= retryCooldownMs
  })
  const coolingDown = attempted.filter((candidate) => {
    const lastAttemptAt = options.attemptedSignalAt.get(autoBettingSignalKey(candidate)) || 0
    return options.now - lastAttemptAt < retryCooldownMs
  })

  return [...neverAttempted, ...retryReady, ...coolingDown].map((candidate, index) => ({
    ...candidate,
    queuePosition: index + 1,
    queueStatus: coolingDown.includes(candidate) ? 'cooldown' : 'ready',
  }))
}

export const selectNextCrownAlertSignal = (
  candidates: AutoBettingSignal[],
  options: CrownAlertSignalSelectionOptions,
): AutoBettingSignal | null => {
  const selected = buildCrownAlertSignalQueue(candidates, options)
    .find((candidate) => candidate.queueStatus === 'ready')
  return selected
    ? candidates.find((candidate) => autoBettingSignalKey(candidate) === autoBettingSignalKey(selected)) || selected
    : null
}

const nonRetriableAutoBettingReasons = new Set([
  'crown_market_locked',
  'crown_place_button_disabled',
  'crown_market_not_found',
  'crown_line_mismatch',
  'crown_betslip_not_cleared',
  'crown_betslip_full',
  'crown_phase_unknown',
  'crown_phase_mismatch',
  'account_stake_limit_reached',
  'target_odds_below_minimum',
])

const completedDuplicateAutoBettingReasons = new Set([
  'duplicate_placed_intent',
])

export const isNonRetriableAutoBettingReason = (reason?: string | null): boolean => (
  Boolean(reason && nonRetriableAutoBettingReasons.has(reason))
)

export const isCompletedDuplicateAutoBettingReason = (reason?: string | null): boolean => (
  Boolean(reason && completedDuplicateAutoBettingReasons.has(reason))
)

export const isCompletedAutoBettingRow = (row: AutoBettingExecutionPlanRow): boolean => (
  row.status === 'passed' || row.stopRetry === true || row.retryable === false
)

export const shouldCompleteCrownSignalForAccounts = (
  rows: AutoBettingExecutionPlanRow[],
  accountIds: string[],
): boolean => {
  const targetIds = new Set(accountIds)
  const targetRows = rows.filter((row) => targetIds.has(row.id))
  return targetRows.length > 0 && targetRows.every(isCompletedAutoBettingRow)
}

export const executionOddsFloor = (signalOdds: number, configuredMinimumOdds: number): number => (
  Number(Math.max(signalOdds, configuredMinimumOdds).toFixed(4))
)

const autoBettingReasonLabels: Record<string, string> = {
  accepted: '已接收',
  auto_betting_disabled: '自动投注已关闭',
  crown_history_verified: '已确认下注',
  crown_receipt_verified: '已确认皇冠回执',
  crown_line_mismatch: '皇冠盘口已变化',
  crown_market_not_found: '皇冠盘口未找到',
  crown_market_locked: '皇冠盘口已锁盘',
  crown_odds_missing: '皇冠水位未读取到',
  crown_place_button_disabled: '皇冠下注按钮不可用',
  crown_stake_input_missing: '皇冠金额输入框未找到',
  crown_stake_input_not_applied: '皇冠金额未成功输入',
  crown_betslip_stake_input_missing: '皇冠注单金额输入框未找到',
  crown_betslip_stake_input_not_applied: '皇冠注单金额未成功输入',
  crown_betslip_not_cleared: '皇冠旧注单未清空',
  crown_betslip_full: '皇冠注单已达到10个选项上限',
  crown_stake_below_minimum: '低于皇冠最低下注金额',
  crown_stake_above_maximum: '高于皇冠最高下注金额',
  crown_execution_error: '皇冠执行异常',
  crown_execution_timeout: '皇冠执行确认超时',
  crown_bet_not_confirmed: '皇冠下注未确认成功',
  crown_network_unstable: '皇冠网络不稳定，请刷新后重试',
  crown_page_activation_failed: '皇冠页面刷新确认失败',
  crown_phase_unknown: '皇冠比赛阶段无法确认',
  crown_phase_mismatch: '皇冠比赛阶段与信号不一致',
  crown_history_unverified: '下注后未确认到历史记录',
  stale_signal: '信号已过期',
  duplicate_active_intent: '已有投注任务处理中，重复信号已跳过',
  duplicate_recent_crown_attempt: '近期已尝试该信号，重复信号已跳过',
  duplicate_placed_intent: '已成功投注，重复信号已跳过',
  account_stake_limit_reached: '单账号投注上限已达到',
  target_odds_below_minimum: '皇冠当前水位低于最低投注水位',
}

export const formatAutoBettingReason = (reason?: string | null): string => {
  const normalized = reason?.trim()
  if (!normalized) return ''
  return autoBettingReasonLabels[normalized] || normalized
}

const alertText = {
  prematch: '赛前',
  live: '滚球',
  leaguePrefix: '联赛：',
  matchPrefix: '比赛：',
  marketPrefix: '盘口：',
  crownPrefix: '皇冠：',
  handicap: '让球',
  total: '大小球',
  home: '主队',
  away: '客队',
}

export const extractCrownAlertSignalCandidates = (
  alerts: OddsAlertRecord[],
  mode: AutoBettingMode,
  limit = 8,
): AutoBettingSignal[] => {
  const candidates: AutoBettingSignal[] = []
  const sortedAlerts = [...alerts].sort((left, right) => right.createdAt - left.createdAt)
  for (const alert of sortedAlerts) {
    candidates.push(...extractCrownAlertSignalsFromAlert(alert, mode))
    if (candidates.length >= limit) return candidates.slice(0, limit)
  }
  return candidates
}

export const filterFreshCrownAlertSignals = (
  candidates: AutoBettingSignal[],
  now: number,
  maxSignalAgeSeconds: number,
): AutoBettingSignal[] => {
  const maxAgeMillis = Math.max(1, Math.min(3600, Number(maxSignalAgeSeconds) || 1)) * 1000
  return candidates.filter((candidate) => (
    typeof candidate.sourceAlertCreatedAt !== 'number' ||
    now - candidate.sourceAlertCreatedAt <= maxAgeMillis
  ))
}

const extractCrownAlertSignalsFromAlert = (
  alert: OddsAlertRecord,
  mode: AutoBettingMode,
): AutoBettingSignal[] => {
  const matchPhase = phaseFromAlert(alert)
  if (matchPhase !== mode) return []

  const lines = alert.message.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
  const leagueName = extractAlertField(lines, alertText.leaguePrefix)
  const matchTitle = extractAlertField(lines, alertText.matchPrefix) || titleMatchName(alert.title)
  if (!matchTitle) return []

  const moves: CrownAlertMove[] = []
  let currentMarket: CrownAlertMarket | null = null
  for (const line of lines) {
    if (line.startsWith(alertText.marketPrefix)) {
      currentMarket = parseCrownAlertMarket(line, matchTitle)
      continue
    }
    if (!currentMarket || !line.startsWith(alertText.crownPrefix)) {
      continue
    }

    const oddsMove = parseCrownOddsMove(line)
    if (!oddsMove) continue
    moves.push({
      ...currentMarket,
      previousOdds: oddsMove.previous,
      currentOdds: oddsMove.current,
    })
  }

  return selectCrownAlertSignals(moves).map((selectedSignal) => ({
    sourceAlertId: alert.id,
    sourceAlertCreatedAt: alert.createdAt,
    modeLabel: modeLabels[matchPhase],
    bettingMode: matchPhase,
    matchPhase,
    leagueName: leagueName || '未分类联赛',
    matchTitle,
    marketType: selectedSignal.marketType,
    marketTitle: selectedSignal.marketTitle,
    lineValue: selectedSignal.lineValue,
    selectionName: selectedSignal.selectionName,
    referenceSourceKey: 'crown',
    targetSourceKey: 'crown',
    referenceOdds: selectedSignal.previousOdds,
    targetOdds: selectedSignal.currentOdds,
    odds: selectedSignal.currentOdds,
    edge: selectedSignal.edge,
    oddsChangeDirection: selectedSignal.oddsChangeDirection,
    bettingLogic: `telegram: ${selectedSignal.selectionName} ${selectedSignal.previousOdds} -> ${selectedSignal.currentOdds}`,
  }))
}

export const buildAutoBettingExecutionPlan = (
  options: AutoBettingExecutionPlanOptions,
): AutoBettingExecutionPlan => {
  const modeLabel = modeLabels[options.mode]
  const signal = options.signal
  const emptyPlan = (summary: string): AutoBettingExecutionPlan => ({
    canExecute: false,
    modeLabel,
    summary,
    totalStake: 0,
    availableAccountCount: 0,
    signal,
    rows: [],
  })

  if (!signal) return emptyPlan('暂无可执行监控信号')
  if (signal.matchPhase !== options.mode) {
    return emptyPlan(`当前监控信号是${signal.modeLabel}，已按${modeLabel}模式跳过`)
  }

  const executionAccounts = options.accounts.filter((account) => account.bettingEnabled !== false)
  const availableAccounts = executionAccounts.filter(isAutomationReady)
  const skipRows = (reason: (account: ExecutionAccount) => string) => executionAccounts.map((account) => (
    skippedRow(account, signal, reason(account))
  ))

  if (!options.enabled) {
    return {
      canExecute: false,
      modeLabel,
      summary: '自动投注未开启',
      totalStake: 0,
      availableAccountCount: availableAccounts.length,
      signal,
      rows: skipRows(() => '自动投注未开启'),
    }
  }

  if (availableAccounts.length === 0) {
    return {
      canExecute: false,
      modeLabel,
      summary: '没有可用账号',
      totalStake: 0,
      availableAccountCount: 0,
      signal,
      rows: skipRows(unavailableReason),
    }
  }

  if (signal.targetOdds < options.minimumBetOdds) {
    return {
      canExecute: false,
      modeLabel,
      summary: '目标水位低于最低投注水位',
      totalStake: 0,
      availableAccountCount: availableAccounts.length,
      signal,
      rows: skipRows(() => '目标水位低于最低投注水位'),
    }
  }

  const stakeAmountPerAccount = Math.floor(Math.min(Math.max(0, options.perAccountLimit), BACKEND_SINGLE_ACCOUNT_LIMIT))
  const accountTotalLimit = Math.floor(Math.max(0, options.betLimit))
  if (stakeAmountPerAccount <= 0 || accountTotalLimit <= 0) {
    return {
      canExecute: false,
      modeLabel,
      summary: '投注金额无效',
      totalStake: 0,
      availableAccountCount: availableAccounts.length,
      signal,
      rows: skipRows(() => '投注金额无效'),
    }
  }

  if (stakeAmountPerAccount > accountTotalLimit) {
    return {
      canExecute: false,
      modeLabel,
      summary: '单账号投注上限低于每次投注金额',
      totalStake: 0,
      availableAccountCount: availableAccounts.length,
      signal,
      rows: skipRows(() => '单账号投注上限低于每次投注金额'),
    }
  }

  const totalStake = stakeAmountPerAccount * availableAccounts.length
  const rows = executionAccounts.map((account) => {
    if (account.bettingEnabled === false) {
      return skippedRow(account, signal, '投注未启用')
    }
    if (!hasAdsPowerProfile(account)) {
      return skippedRow(account, signal, '未绑定 AdsPower 档案')
    }
    if (account.adsPowerStatus === 'closed') {
      return skippedRow(account, signal, 'AdsPower 环境未打开')
    }
    if (account.adsPowerStatus === 'error') {
      return skippedRow(account, signal, 'AdsPower 环境异常')
    }
    if (account.adsPowerStatus !== 'opened') {
      return skippedRow(account, signal, 'AdsPower 环境未打开')
    }
    if (account.status !== 'success') {
      return skippedRow(account, signal, '账号未在线')
    }
    return {
      id: account.id,
      accountName: account.displayName,
      status: 'passed' as const,
      statusLabel: '待下注',
      modeLabel: signal.modeLabel,
      bettingMode: signal.bettingMode,
      matchPhase: signal.matchPhase,
      leagueName: signal.leagueName,
      matchTitle: signal.matchTitle,
      marketType: signal.marketType,
      marketTitle: signal.marketTitle,
      lineValue: signal.lineValue,
      selectionName: signal.selectionName,
      referenceSourceKey: signal.referenceSourceKey,
      targetSourceKey: signal.targetSourceKey,
      referenceOdds: signal.referenceOdds,
      targetOdds: signal.targetOdds,
      odds: signal.odds,
      edge: signal.edge,
      oddsChangeDirection: signal.oddsChangeDirection,
      bettingLogic: signal.bettingLogic,
      capturedAt: signal.sourceAlertCreatedAt || Date.now(),
      stakeAmount: stakeAmountPerAccount,
      reason: '盘口和水位通过，等待下单',
    }
  })

  return {
    canExecute: true,
    modeLabel,
    summary: `已检查 ${availableAccounts.length} 个账号`,
    totalStake,
    availableAccountCount: availableAccounts.length,
    signal,
    rows,
  }
}

const phaseFromAlert = (alert: OddsAlertRecord): AutoBettingMode | null => {
  const text = `${alert.title}\n${alert.message}`
  if (text.includes(alertText.live)) return 'live'
  if (text.includes(alertText.prematch)) return 'prematch'
  return null
}

const extractAlertField = (lines: string[], prefix: string) => (
  lines.find((line) => line.startsWith(prefix))?.slice(prefix.length).trim() || ''
)

const titleMatchName = (title: string) => {
  const parts = title.split(/[:：]/)
  return parts.length > 1 ? parts.slice(1).join('：').trim() : ''
}

const parseCrownOddsMove = (line: string): { previous: number; current: number } | null => {
  const move = line.match(/(-?\d+(?:\.\d+)?)\s*->\s*(-?\d+(?:\.\d+)?)/)
  if (!move) return null
  const previous = Number(move[1])
  const current = Number(move[2])
  if (!Number.isFinite(previous) || !Number.isFinite(current) || previous <= 0 || current <= 0) return null
  return { previous, current }
}

const parseCrownAlertMarket = (
  line: string,
  matchTitle: string,
): CrownAlertMarket | null => {
  const content = line.slice(alertText.marketPrefix.length).trim()
  const [marketText = '', selectionText = '', ...lineParts] = content.split(/\s+/)
  const lineValue = lineParts.join(' ')
  const [homeTeam = '', awayTeam = ''] = matchTitle.split(/\s+vs\s+/i).map((team) => team.trim())

  if (marketText === alertText.handicap) {
    const selectionName = selectionText === alertText.home
      ? homeTeam
      : selectionText === alertText.away
        ? awayTeam
        : selectionText
    return {
      marketType: 'handicap',
      marketTitle: lineValue ? `${alertText.handicap} ${lineValue}` : alertText.handicap,
      lineValue,
      selectionName,
      selectionRole: selectionText === alertText.home ? 'home' : selectionText === alertText.away ? 'away' : selectionText,
    }
  }

  if (marketText === alertText.total) {
    const selectionRole = selectionText.includes('大') ? 'over' : selectionText.includes('小') ? 'under' : selectionText
    return {
      marketType: 'total',
      marketTitle: lineValue ? `${alertText.total} ${lineValue}` : alertText.total,
      lineValue,
      selectionName: selectionText,
      selectionRole,
    }
  }

  return null
}

type CrownAlertMarket = Pick<AutoBettingSignal, 'marketType' | 'marketTitle' | 'lineValue' | 'selectionName'> & {
  selectionRole: string
}

type CrownAlertMove = CrownAlertMarket & {
  previousOdds: number
  currentOdds: number
}

type SelectedCrownAlertSignal = CrownAlertMove & {
  edge: number
  oddsChangeDirection: 'rise' | 'drop'
}

const selectCrownAlertSignals = (moves: CrownAlertMove[]): SelectedCrownAlertSignal[] => {
  return moves
    .filter((move) => move.currentOdds !== move.previousOdds)
    .map((move) => ({
      ...move,
      edge: Number(Math.abs(move.currentOdds - move.previousOdds).toFixed(4)),
      oddsChangeDirection: move.currentOdds > move.previousOdds ? 'rise' as const : 'drop' as const,
    }))
}

const hasAdsPowerProfile = (account: ExecutionAccount) => Boolean(account.adsPowerProfileId?.trim())

const isAutomationReady = (account: ExecutionAccount) => (
  account.bettingEnabled !== false &&
  hasAdsPowerProfile(account) &&
  account.adsPowerStatus === 'opened' &&
  account.status === 'success'
)

const unavailableReason = (account: ExecutionAccount) => {
  if (account.bettingEnabled === false) return '投注未启用'
  if (!hasAdsPowerProfile(account)) return '未绑定 AdsPower 档案'
  if (account.adsPowerStatus === 'closed') return 'AdsPower 环境未打开'
  if (account.adsPowerStatus === 'error') return 'AdsPower 环境异常'
  if (account.adsPowerStatus !== 'opened') return 'AdsPower 环境未打开'
  return '账号未在线'
}

const skippedRow = (
  account: ExecutionAccount,
  signal: AutoBettingSignal,
  reason: string,
): AutoBettingExecutionPlanRow => ({
  id: account.id,
  accountName: account.displayName,
  status: 'skipped',
  statusLabel: '跳过',
  modeLabel: signal.modeLabel,
  bettingMode: signal.bettingMode,
  matchPhase: signal.matchPhase,
  leagueName: signal.leagueName,
  matchTitle: signal.matchTitle,
  marketType: signal.marketType,
  marketTitle: signal.marketTitle,
  lineValue: signal.lineValue,
  selectionName: signal.selectionName,
  referenceSourceKey: signal.referenceSourceKey,
  targetSourceKey: signal.targetSourceKey,
  referenceOdds: signal.referenceOdds,
  targetOdds: signal.targetOdds,
  odds: signal.odds,
  edge: signal.edge,
  oddsChangeDirection: signal.oddsChangeDirection,
  bettingLogic: signal.bettingLogic,
  capturedAt: signal.sourceAlertCreatedAt || Date.now(),
  stakeAmount: 0,
  reason,
})
