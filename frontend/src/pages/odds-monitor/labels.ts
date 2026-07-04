import type { MatchDetail, MatchFilterKey, MatchItem, PlatformKey } from './types'

export const platformColors: Record<PlatformKey, string> = {
  crown: '#16a34a',
}

export const platformLabels: Record<PlatformKey, string> = {
  crown: '皇冠',
}

export const marketTitleLabels: Record<string, string> = {
  handicap: '让球盘',
  total: '大小球',
  moneyline: '胜平负',
  winner: '胜平负',
  odds: '赔率',
}

export const selectionLabels: Record<string, string> = {
  home: '主队',
  away: '客队',
  over: '大球',
  under: '小球',
  draw: '平局',
}

export const matchFilterLabels: Record<MatchFilterKey, string> = {
  all: '全部',
  single: '皇冠单源',
}

const teamNameAliases: Record<string, string> = {
  arsenal: '阿森纳',
  chelsea: '切尔西',
  'real madrid': '皇家马德里',
  barcelona: '巴塞罗那',
  inter: '国际米兰',
  'inter milan': '国际米兰',
  'bayern munich': '拜仁慕尼黑',
  'fc bayern munich': '拜仁慕尼黑',
}

const leagueNameAliases: Record<string, string> = {
  英超: '英格兰超级联赛',
  西甲: '西班牙甲组联赛',
  欧冠: '欧洲冠军联赛',
  'england premier league': '英格兰超级联赛',
  'spanish la liga': '西班牙甲组联赛',
  'uefa champions league': '欧洲冠军联赛',
}

const statusLabels: Record<string, string> = {
  scheduled: '赛前',
  prematch: '赛前',
  not_started: '赛前',
  live: '滚球',
  inplay: '滚球',
  in_play: '滚球',
  finished: '完场',
  closed: '完场',
  模拟: '模拟',
  赛前: '赛前',
  滚球: '滚球',
}

const fallbackPlatformSets: PlatformKey[][] = [
  ['crown'],
]

export const visiblePlatformKeys: PlatformKey[] = ['crown']
export const isVisiblePlatform = (value?: string): value is PlatformKey => value === 'crown'

export const localizeTeamName = (value: string) => teamNameAliases[value.trim().toLowerCase()] || value
export const localizeLeagueName = (value: string) => leagueNameAliases[value.trim().toLowerCase()] || value || '未分类联赛'
export const localizeStatus = (value: string) => statusLabels[value.trim().toLowerCase()] || statusLabels[value] || value

export const matchName = (match: Pick<MatchItem, 'homeTeam' | 'awayTeam'>) =>
  `${localizeTeamName(match.homeTeam)} 对 ${localizeTeamName(match.awayTeam)}`

export const formatAxisTime = (timestamp: number | string) =>
  new Date(Number(timestamp)).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  })

export const formatDateTime = (timestamp: number) =>
  new Date(timestamp).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })

export const toAsianOdd = (value?: number) => {
  if (typeof value !== 'number') return value
  return value > 1 ? value - 1 : value
}

export const formatOdd = (value?: number) => (typeof value === 'number' ? value.toFixed(3) : '-')
export const formatAsianOdd = (value?: number) => formatOdd(toAsianOdd(value))

export const createFallbackHistory = (): MatchDetail['oddsHistory'] => {
  const now = Date.now()
  return Array.from({ length: 10 }, (_, index) => ({
    timestamp: now - (9 - index) * 5 * 60 * 1000,
    crown: 1.9 + index * 0.012,
  }))
}

export const ensureChartHistory = (history: MatchDetail['oddsHistory']) =>
  history.length > 0 ? history : createFallbackHistory()

export const getMatchedPlatforms = (match: MatchItem, index: number): PlatformKey[] => {
  const visibleMatched = (match.matchedPlatforms || []).filter(isVisiblePlatform)
  if (visibleMatched.length) return visibleMatched
  return fallbackPlatformSets[index % fallbackPlatformSets.length].slice(0, 1)
}
