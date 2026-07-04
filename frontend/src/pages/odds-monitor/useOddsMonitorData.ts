import { useEffect, useMemo, useRef, useState } from 'react'
import { filterMatchesBySearchQuery, getSearchJumpMatch, shouldAutoJumpForSearchQuery } from '../OddsMonitorSearch'
import {
  createPlatformMatchViews,
  selectPlatformDetail,
  type OddsMonitorPlatformMatchView,
} from '../OddsMonitorPlatformView'
import { fetchOddsMonitorDashboard, fetchOddsMonitorMatchDetail } from './api'
import {
  ensureChartHistory,
  getMatchedPlatforms,
  localizeLeagueName,
  localizeStatus,
  localizeTeamName,
} from './labels'
import { buildMarketGroups } from './marketGroups'
import type { DashboardData, MatchDetail, MatchFilterKey, PlatformKey } from './types'
import type { OddsMonitorMatchGroup } from './MatchListPanel'

export const useOddsMonitorData = () => {
  const [loading, setLoading] = useState(true)
  const [data, setData] = useState<DashboardData | null>(null)
  const [selectedDetail, setSelectedDetail] = useState<MatchDetail | null>(null)
  const [activeMatchViewKey, setActiveMatchViewKey] = useState<string | null>(null)
  const [matchSearchQuery, setMatchSearchQuery] = useState('')
  const [matchFilter, setMatchFilter] = useState<MatchFilterKey>('all')
  const lastAutoJumpQuery = useRef('')

  useEffect(() => {
    fetchOddsMonitorDashboard()
      .then((dashboard) => {
        setData(dashboard)
        setSelectedDetail(dashboard.selectedMatch || null)
      })
      .finally(() => setLoading(false))
  }, [])

  const matches = useMemo(() => {
    return (data?.matches || []).map((match, index) => ({
      ...match,
      leagueName: localizeLeagueName(match.leagueName),
      homeTeam: localizeTeamName(match.homeTeam),
      awayTeam: localizeTeamName(match.awayTeam),
      status: localizeStatus(match.status),
      matchedPlatforms: getMatchedPlatforms(match, index),
    }))
  }, [data?.matches])

  const matchViews = useMemo(() => createPlatformMatchViews(matches), [matches])
  const filteredByPlatform = useMemo(() => {
    return matchViews.filter((match) => {
      const platforms = match.matchedPlatforms || []
      if (matchFilter === 'single') return platforms.length === 1
      return true
    })
  }, [matchFilter, matchViews])
  const filteredMatches = useMemo(
    () => filterMatchesBySearchQuery(filteredByPlatform, matchSearchQuery),
    [filteredByPlatform, matchSearchQuery],
  )

  const statusCounts = useMemo(() => {
    const platformCounts = matches.reduce((counts, match) => {
      (match.matchedPlatforms || []).forEach((platform) => {
        counts[platform] += 1
      })
      return counts
    }, { crown: 0 } as Record<PlatformKey, number>)
    return {
      ...platformCounts,
      isolated: matches.filter((match) => (match.matchedPlatforms || []).length === 1).length,
    }
  }, [matches])

  const groupedMatches = useMemo<OddsMonitorMatchGroup[]>(() => {
    const groups = new Map<string, OddsMonitorPlatformMatchView[]>()
    filteredMatches.forEach((match) => {
      const leagueName = localizeLeagueName(match.leagueName)
      groups.set(leagueName, [...(groups.get(leagueName) || []), match])
    })
    return Array.from(groups.entries()).map(([leagueName, leagueMatches]) => ({
      leagueName,
      matches: leagueMatches,
    }))
  }, [filteredMatches])

  useEffect(() => {
    if (!shouldAutoJumpForSearchQuery(lastAutoJumpQuery.current, matchSearchQuery)) {
      if (!matchSearchQuery.trim()) lastAutoJumpQuery.current = ''
      return
    }
    const jumpMatch = getSearchJumpMatch(matchViews, matchSearchQuery)
    if (jumpMatch) {
      lastAutoJumpQuery.current = matchSearchQuery
      setActiveMatchViewKey(jumpMatch.viewKey)
    }
  }, [matchSearchQuery, matchViews])

  const activeMatch = matchViews.find((match) => match.viewKey === activeMatchViewKey) || matchViews[0]

  useEffect(() => {
    if (!activeMatch?.id) return undefined
    if (selectedDetail?.match.id === activeMatch.id) return undefined
    let cancelled = false
    fetchOddsMonitorMatchDetail(activeMatch.id)
      .then((detail) => {
        if (!cancelled && detail) setSelectedDetail(detail)
      })
    return () => {
      cancelled = true
    }
  }, [activeMatch?.id, selectedDetail?.match.id])

  const selected = useMemo(() => {
    if (!activeMatch) return selectedDetail || data?.selectedMatch
    if (selectedDetail?.match.id === activeMatch.id) return selectPlatformDetail(selectedDetail, activeMatch)
    return { match: activeMatch, metrics: [], oddsHistory: [] }
  }, [activeMatch, data?.selectedMatch, selectedDetail])

  const localizedSelected = useMemo(() => {
    if (!selected) return selected
    return {
      ...selected,
      match: {
        ...selected.match,
        leagueName: localizeLeagueName(selected.match.leagueName),
        homeTeam: localizeTeamName(selected.match.homeTeam),
        awayTeam: localizeTeamName(selected.match.awayTeam),
        status: localizeStatus(selected.match.status),
      },
    }
  }, [selected])

  const chartHistory = useMemo(
    () => (localizedSelected ? ensureChartHistory(localizedSelected.oddsHistory) : []),
    [localizedSelected],
  )
  const chartTimestamps = useMemo(() => chartHistory.map((item) => item.timestamp), [chartHistory])
  const marketGroups = useMemo(
    () => (localizedSelected ? buildMarketGroups(chartHistory, localizedSelected) : []),
    [chartHistory, localizedSelected],
  )

  const jumpToSearchMatch = (value: string) => {
    const jumpMatch = getSearchJumpMatch(matchViews, value)
    if (jumpMatch) {
      lastAutoJumpQuery.current = value
      setActiveMatchViewKey(jumpMatch.viewKey)
    }
  }

  return {
    loading,
    matches,
    matchViews,
    filteredMatches,
    groupedMatches,
    statusCounts,
    matchSearchQuery,
    setMatchSearchQuery,
    matchFilter,
    setMatchFilter,
    activeMatch,
    activeMatchViewKey,
    setActiveMatchViewKey,
    localizedSelected,
    chartTimestamps,
    marketGroups,
    jumpToSearchMatch,
  }
}
