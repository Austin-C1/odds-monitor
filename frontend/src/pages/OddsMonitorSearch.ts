export type OddsMonitorSearchPlatform = 'pinnacle' | 'crown' | 'polymarket'

export type OddsMonitorSearchMatch = {
  id: number
  leagueName: string
  homeTeam: string
  awayTeam: string
  matchedPlatforms?: readonly OddsMonitorSearchPlatform[]
}

const platformSearchLabels: Record<OddsMonitorSearchPlatform, string> = {
  pinnacle: '平博 pinnacle',
  crown: '皇冠 crown',
  polymarket: 'polymarket',
}

const normalizeSearchText = (value: string) =>
  value
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '')

export const shouldAutoJumpForSearchQuery = (lastQuery: string, nextQuery: string) => {
  const normalizedNextQuery = normalizeSearchText(nextQuery)
  if (!normalizedNextQuery) return false
  return normalizeSearchText(lastQuery) !== normalizedNextQuery
}

export const getMatchSearchText = (match: OddsMonitorSearchMatch) =>
  normalizeSearchText([
    match.leagueName,
    match.homeTeam,
    match.awayTeam,
    ...(match.matchedPlatforms || []).map((platform) => platformSearchLabels[platform] || platform),
  ].join(' '))

export const filterMatchesBySearchQuery = <T extends OddsMonitorSearchMatch>(
  matches: readonly T[],
  query: string,
): T[] => {
  const normalizedQuery = normalizeSearchText(query)
  if (!normalizedQuery) return [...matches]
  return matches.filter((match) => getMatchSearchText(match).includes(normalizedQuery))
}

export const getSearchJumpMatch = <T extends OddsMonitorSearchMatch>(
  matches: readonly T[],
  query: string,
): T | undefined => filterMatchesBySearchQuery(matches, query)[0]
