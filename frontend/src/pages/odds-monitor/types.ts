export type PlatformKey = 'crown'

export type MatchItem = {
  id: number
  leagueName: string
  homeTeam: string
  awayTeam: string
  startTime: number
  status: string
  sourceCount: number
  alertCount: number
  matchedPlatforms?: PlatformKey[]
}

export type MatchDetail = {
  match: MatchItem
  metrics: Array<{ label: string; value: string; trend: string; sourceKey?: PlatformKey }>
  oddsHistory: Array<{ timestamp: number; crown: number }>
}

export type DashboardData = { matches: MatchItem[]; selectedMatch?: MatchDetail }
export type MatchFilterKey = 'all' | 'single'

export type MarketOutcome = {
  selection: string
  line?: string
  odds: Partial<Record<PlatformKey, number>>
}

export type MarketSeries = {
  platform: PlatformKey
  label: string
  values: number[]
  latestText: string
}

export type MarketGroup = {
  key: string
  title: string
  description: string
  platformKeys: PlatformKey[]
  rows: MarketOutcome[]
  series: MarketSeries[]
}
