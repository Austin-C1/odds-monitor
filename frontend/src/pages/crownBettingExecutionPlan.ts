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

const autoBettingReasonLabels: Record<string, string> = {
  accepted: '已接收',
  crown_history_verified: '已确认下注',
  crown_receipt_verified: '已确认皇冠回执',
  crown_line_mismatch: '皇冠盘口已变化',
  crown_market_not_found: '皇冠盘口未找到',
  crown_market_locked: '皇冠盘口已锁盘',
  crown_odds_missing: '皇冠水位未读取到',
  crown_place_button_disabled: '皇冠下注按钮不可用',
  crown_stake_input_missing: '皇冠金额输入框未找到',
  crown_betslip_stake_input_missing: '皇冠注单金额输入框未找到',
  crown_stake_below_minimum: '低于皇冠最低下注金额',
  crown_stake_above_maximum: '高于皇冠最高下注金额',
  crown_execution_error: '皇冠执行异常',
  crown_execution_timeout: '皇冠执行确认超时',
  crown_history_unverified: '下注后未确认到历史记录',
  stale_signal: '信号已过期',
  duplicate_placed_intent: '已成功投注，重复信号已跳过',
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

export const extractLatestCrownAlertSignal = (
  alerts: OddsAlertRecord[],
  mode: AutoBettingMode,
): AutoBettingSignal | null => {
  return extractCrownAlertSignalCandidates(alerts, mode, 1)[0] || null
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

export const filterLatestCrownAlertSignalBatch = (
  candidates: AutoBettingSignal[],
): AutoBettingSignal[] => {
  const latestCreatedAt = candidates[0]?.sourceAlertCreatedAt
  if (typeof latestCreatedAt !== 'number') return candidates
  return candidates.filter((candidate) => candidate.sourceAlertCreatedAt === latestCreatedAt)
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

  const singleAccountLimit = Math.min(Math.max(0, options.perAccountLimit), BACKEND_SINGLE_ACCOUNT_LIMIT)
  const maxByAccounts = singleAccountLimit * availableAccounts.length
  const totalStake = Math.max(0, Math.min(options.betLimit, maxByAccounts))
  if (totalStake <= 0) {
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

  const stakeByAccount = splitAmount(totalStake, availableAccounts.length)
  let availableIndex = 0
  const rows = executionAccounts.map((account) => {
    if (account.bettingEnabled === false) {
      return skippedRow(account, signal, '投注未启用')
    }
    if (!hasAdsPowerProfile(account)) {
      return skippedRow(account, signal, '未绑定 AdsPower Profile')
    }
    if (account.adsPowerStatus !== 'opened') {
      return skippedRow(account, signal, 'AdsPower 环境未打开')
    }
    if (account.status !== 'success') {
      return skippedRow(account, signal, '账号未在线')
    }
    const stakeAmount = stakeByAccount[availableIndex] || 0
    availableIndex += 1
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
      stakeAmount,
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
  if (!hasAdsPowerProfile(account)) return '未绑定 AdsPower Profile'
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

const splitAmount = (totalAmount: number, parts: number): number[] => {
  if (parts <= 0) return []
  const totalCents = Math.round(totalAmount * 100)
  const baseCents = Math.floor(totalCents / parts)
  const remainder = totalCents % parts
  return Array.from({ length: parts }, (_value, index) => (
    (baseCents + (index < remainder ? 1 : 0)) / 100
  ))
}
