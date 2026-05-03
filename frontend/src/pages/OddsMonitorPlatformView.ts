export type OddsMonitorPlatformKey = 'pinnacle' | 'crown' | 'polymarket'

export type OddsMonitorPlatformMatch = {
  id: number
  leagueName: string
  homeTeam: string
  awayTeam: string
  startTime: number
  status: string
  sourceCount: number
  alertCount: number
  matchedPlatforms?: OddsMonitorPlatformKey[]
}

export type OddsMonitorPlatformMatchView = OddsMonitorPlatformMatch & {
  viewKey: string
  viewPlatform?: OddsMonitorPlatformKey
  sourceMatch: OddsMonitorPlatformMatch
  matchedPlatforms: OddsMonitorPlatformKey[]
}

export const createPlatformMatchViews = (
  matches: OddsMonitorPlatformMatch[],
): OddsMonitorPlatformMatchView[] =>
  matches.map((match) => ({
    ...match,
    viewKey: String(match.id),
    sourceMatch: match,
    matchedPlatforms: match.matchedPlatforms?.length ? [...match.matchedPlatforms] : [],
  }))

export const selectPlatformDetail = <T extends { metrics: Array<{ sourceKey?: string }>; match: OddsMonitorPlatformMatch }>(
  detail: T,
  view: OddsMonitorPlatformMatchView,
): T => ({
  ...detail,
  match: {
    ...detail.match,
    matchedPlatforms: view.matchedPlatforms,
    sourceCount: view.matchedPlatforms.length,
  },
  metrics: detail.metrics,
})
