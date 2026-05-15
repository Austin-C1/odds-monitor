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

export type OddsMonitorMatch = {
  id: number
  leagueName: string
  homeTeam: string
  awayTeam: string
  startTime: number
  status: string
  sourceCount: number
  alertCount: number
  matchedPlatforms: string[]
}

export type OddsMonitorMetric = {
  label: string
  value: string
  trend: string
  sourceKey?: string | null
}

export type OddsMonitorDashboard = {
  matches: OddsMonitorMatch[]
  selectedMatch?: {
    match: OddsMonitorMatch
    metrics: OddsMonitorMetric[]
    oddsHistory: unknown[]
    platformMatches?: unknown[]
  } | null
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
  referenceSourceKey: 'pinnacle' | 'crown'
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
  minimumEdge: number
  minimumBetOdds: number
  oddsTolerance: number
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
  referenceSourceKey: 'pinnacle' | 'crown'
  targetSourceKey: 'crown'
  referenceOdds: number
  targetOdds: number
  odds: number
  edge: number
  oddsChangeDirection?: 'rise' | 'drop'
  bettingLogic: string
  oddsTolerance: number
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

const marketTitleLabels: Record<AutoBettingSignal['marketType'], string> = {
  handicap: '让球盘',
  total: '大小球',
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
  for (let index = 0; index < lines.length - 1; index += 1) {
    const marketLine = lines[index]
    const oddsLine = lines[index + 1]
    if (!marketLine.startsWith(alertText.marketPrefix) || !oddsLine.startsWith(alertText.crownPrefix)) {
      continue
    }

    const oddsMove = parseCrownOddsMove(oddsLine)
    if (!oddsMove) continue

    const market = parseCrownAlertMarket(marketLine, matchTitle)
    if (!market) continue
    moves.push({
      ...market,
      previousOdds: oddsMove.previous,
      currentOdds: oddsMove.current,
    })
  }

  return selectReversedCrownAlertSignals(moves).map((reversedSignal) => ({
    sourceAlertId: alert.id,
    sourceAlertCreatedAt: alert.createdAt,
    modeLabel: modeLabels[matchPhase],
    bettingMode: matchPhase,
    matchPhase,
    leagueName: leagueName || '未分类联赛',
    matchTitle,
    marketType: reversedSignal.marketType,
    marketTitle: reversedSignal.marketTitle,
    lineValue: reversedSignal.lineValue,
    selectionName: reversedSignal.selectionName,
    referenceSourceKey: 'crown',
    targetSourceKey: 'crown',
    referenceOdds: reversedSignal.previousOdds,
    targetOdds: reversedSignal.currentOdds,
    odds: reversedSignal.currentOdds,
    edge: reversedSignal.edge,
    oddsChangeDirection: 'drop',
    bettingLogic: `reverse: ${reversedSignal.risingSelectionName} 升水，改投对面掉水方 ${reversedSignal.selectionName}`,
  }))
}

export const extractLatestMonitorSignal = (dashboard: OddsMonitorDashboard): AutoBettingSignal | null => {
  const selected = dashboard.selectedMatch
  if (!selected?.metrics?.length) return null

  const matchPhase = phaseFromStatus(selected.match.status)
  const metricsByLabel = selected.metrics.reduce<Map<string, OddsMonitorMetric[]>>((groups, metric) => {
    const key = normalizeLabel(metric.label)
    if (!key) return groups
    const list = groups.get(key) || []
    list.push(metric)
    groups.set(key, list)
    return groups
  }, new Map<string, OddsMonitorMetric[]>())

  for (const [label, metrics] of metricsByLabel) {
    const pinnacleMetric = metrics.find((metric) => normalizeSource(metric.sourceKey) === 'pinnacle')
    const crownMetric = metrics.find((metric) => normalizeSource(metric.sourceKey) === 'crown')
    const referenceOdds = parseOdds(pinnacleMetric?.value)
    const targetOdds = parseOdds(crownMetric?.value)
    if (!pinnacleMetric || !crownMetric || referenceOdds == null || targetOdds == null) continue

    const market = parseMarketLabel(label, selected.match)
    if (!market) continue

    return {
      modeLabel: modeLabels[matchPhase],
      bettingMode: matchPhase,
      matchPhase,
      leagueName: selected.match.leagueName || '未分类联赛',
      matchTitle: `${selected.match.homeTeam} vs ${selected.match.awayTeam}`,
      marketType: market.marketType,
      marketTitle: market.marketTitle,
      lineValue: market.lineValue,
      selectionName: market.selectionName,
      referenceSourceKey: 'pinnacle',
      targetSourceKey: 'crown',
      referenceOdds,
      targetOdds,
      odds: targetOdds,
      edge: Number((targetOdds + 1 - referenceOdds).toFixed(4)),
      oddsChangeDirection: 'rise',
      bettingLogic: 'Pinnacle reference advantage versus Crown target odds',
    }
  }
  return null
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
    skippedRow(account, signal, options.oddsTolerance, reason(account))
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

  if (signal.edge < options.minimumEdge) {
    return {
      canExecute: false,
      modeLabel,
      summary: '赔率优势不足',
      totalStake: 0,
      availableAccountCount: availableAccounts.length,
      signal,
      rows: skipRows(() => '赔率优势不足'),
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
      return skippedRow(account, signal, options.oddsTolerance, '投注未启用')
    }
    if (!hasAdsPowerProfile(account)) {
      return skippedRow(account, signal, options.oddsTolerance, '未绑定 AdsPower Profile')
    }
    if (account.adsPowerStatus !== 'opened') {
      return skippedRow(account, signal, options.oddsTolerance, 'AdsPower 环境未打开')
    }
    if (account.status !== 'success') {
      return skippedRow(account, signal, options.oddsTolerance, '账号未在线')
    }
    const stakeAmount = stakeByAccount[availableIndex] || 0
    availableIndex += 1
    return {
      id: account.id,
      accountName: account.displayName,
      status: 'passed' as const,
      statusLabel: '通过',
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
      oddsTolerance: options.oddsTolerance,
      stakeAmount,
      reason: '实际测试通过',
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

const parseMarketLabel = (
  normalizedLabel: string,
  match: Pick<OddsMonitorMatch, 'homeTeam' | 'awayTeam'>,
): Pick<AutoBettingSignal, 'marketType' | 'marketTitle' | 'lineValue' | 'selectionName'> | null => {
  const [marketType, selection = '', ...lineParts] = normalizedLabel.split(' ')
  const lineValue = lineParts.join(' ')
  if (marketType === 'handicap') {
    const selectionName = selection === 'home'
      ? match.homeTeam
      : selection === 'away'
        ? match.awayTeam
        : selection
    return {
      marketType: 'handicap',
      marketTitle: lineValue ? `${marketTitleLabels.handicap} ${lineValue}` : marketTitleLabels.handicap,
      lineValue,
      selectionName,
    }
  }
  if (marketType === 'total') {
    const selectionName = selection === 'over' ? '大球' : selection === 'under' ? '小球' : selection
    return {
      marketType: 'total',
      marketTitle: lineValue ? `${marketTitleLabels.total} ${lineValue}` : marketTitleLabels.total,
      lineValue,
      selectionName,
    }
  }
  return null
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

type ReversedCrownAlertSignal = CrownAlertMove & {
  edge: number
  risingSelectionName: string
}

const selectReversedCrownAlertSignals = (moves: CrownAlertMove[]): ReversedCrownAlertSignal[] => {
  const signals: ReversedCrownAlertSignal[] = []
  for (const move of moves) {
    if (move.currentOdds <= move.previousOdds) continue
    const opposite = moves.find((candidate) => (
      candidate.marketType === move.marketType &&
      candidate.lineValue === move.lineValue &&
      candidate.selectionRole === oppositeSelectionRole(move.selectionRole)
    ))
    if (!opposite || opposite.currentOdds >= opposite.previousOdds) continue
    signals.push({
      ...opposite,
      edge: Number(Math.max(
        move.currentOdds - move.previousOdds,
        opposite.previousOdds - opposite.currentOdds,
      ).toFixed(4)),
      risingSelectionName: move.selectionName,
    })
  }
  return signals
}

const oppositeSelectionRole = (selectionRole: string) => {
  if (selectionRole === 'home') return 'away'
  if (selectionRole === 'away') return 'home'
  if (selectionRole === 'over') return 'under'
  if (selectionRole === 'under') return 'over'
  return ''
}

const phaseFromStatus = (status: string): AutoBettingMode => {
  const normalized = status.trim().toLowerCase()
  return ['live', 'inplay', 'in_play', '滚球'].includes(normalized) ? 'live' : 'prematch'
}

const normalizeLabel = (value: string) => value.trim().replace(/\s+/g, ' ').toLowerCase()
const normalizeSource = (value?: string | null) => value?.trim().toLowerCase()

const parseOdds = (value?: string) => {
  const numericValue = Number(value)
  return Number.isFinite(numericValue) && numericValue > 0 ? numericValue : null
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
  oddsTolerance: number,
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
  oddsTolerance,
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
