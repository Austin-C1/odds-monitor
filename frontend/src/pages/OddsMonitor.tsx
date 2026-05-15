import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, Checkbox, Collapse, Empty, Input, List, Space, Spin, Tag, Typography } from 'antd'
import * as echarts from 'echarts'
import { apiClient } from '../services/api'
import { filterMatchesBySearchQuery, getSearchJumpMatch, shouldAutoJumpForSearchQuery } from './OddsMonitorSearch'
import {
  createPlatformMatchViews,
  selectPlatformDetail,
  type OddsMonitorPlatformMatchView,
} from './OddsMonitorPlatformView'
import './OddsMonitor.css'

const { Title, Text } = Typography

type ApiResponse<T> = { code: number; data: T; msg: string }
type PlatformKey = 'pinnacle' | 'crown'

type MatchItem = {
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

type MatchDetail = {
  match: MatchItem
  metrics: Array<{ label: string; value: string; trend: string; sourceKey?: PlatformKey }>
  oddsHistory: Array<{ timestamp: number; pinnacle: number; crown: number }>
}

type DashboardData = { matches: MatchItem[]; selectedMatch?: MatchDetail }
type MatchFilterKey = 'all' | 'both' | 'pinnacle-crown' | 'single' | 'match-risk' | 'market-risk'

type MarketOutcome = {
  selection: string
  line?: string
  odds: Partial<Record<PlatformKey, number>>
}

type MarketSeries = {
  platform: PlatformKey
  label: string
  values: number[]
  latestText: string
}

type MarketGroup = {
  key: string
  title: string
  description: string
  platformKeys: PlatformKey[]
  rows: MarketOutcome[]
  series: MarketSeries[]
}

const platformColors: Record<PlatformKey, string> = {
  pinnacle: '#ef4444',
  crown: '#16a34a',
}

const platformLabels: Record<PlatformKey, string> = {
  pinnacle: '平博',
  crown: '皇冠',
}

const marketTitleLabels: Record<string, string> = {
  handicap: '让球盘',
  total: '大小球',
  moneyline: '胜平负',
  winner: '胜平负',
  odds: '赔率',
}

const selectionLabels: Record<string, string> = {
  home: '主队',
  away: '客队',
  over: '大球',
  under: '小球',
  draw: '平局',
}

const matchFilterLabels: Record<MatchFilterKey, string> = {
  all: '全部',
  both: '双平台都有',
  'pinnacle-crown': '平博+皇冠',
  single: '只有单平台',
  'match-risk': '疑似匹配失败',
  'market-risk': '疑似盘口缺失',
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
  '英超': '英格兰超级联赛',
  '西甲': '西班牙甲组联赛',
  '欧冠': '欧洲冠军联赛',
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
  ['pinnacle', 'crown'],
  ['pinnacle'],
  ['crown'],
]

const localizeTeamName = (value: string) => teamNameAliases[value.trim().toLowerCase()] || value
const localizeLeagueName = (value: string) => leagueNameAliases[value.trim().toLowerCase()] || value || '未分类联赛'
const localizeStatus = (value: string) => statusLabels[value.trim().toLowerCase()] || statusLabels[value] || value
const matchName = (match: Pick<MatchItem, 'homeTeam' | 'awayTeam'>) =>
  `${localizeTeamName(match.homeTeam)} 对 ${localizeTeamName(match.awayTeam)}`

const formatAxisTime = (timestamp: number | string) =>
  new Date(Number(timestamp)).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  })

const formatDateTime = (timestamp: number) =>
  new Date(timestamp).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })

const toAsianOdd = (value?: number) => {
  if (typeof value !== 'number') return value
  return value > 1 ? value - 1 : value
}

const formatOdd = (value?: number) => (typeof value === 'number' ? value.toFixed(3) : '-')
const formatAsianOdd = (value?: number) => formatOdd(toAsianOdd(value))
const visiblePlatformKeys: PlatformKey[] = ['pinnacle', 'crown']
const isVisiblePlatform = (value?: string): value is PlatformKey => value === 'pinnacle' || value === 'crown'

const createFallbackHistory = (): MatchDetail['oddsHistory'] => {
  const now = Date.now()
  return Array.from({ length: 10 }, (_, index) => ({
    timestamp: now - (9 - index) * 5 * 60 * 1000,
    pinnacle: 1.92 + index * 0.015,
    crown: 1.9 + index * 0.012,
  }))
}

const ensureChartHistory = (history: MatchDetail['oddsHistory']) =>
  history.length > 0 ? history : createFallbackHistory()

const getMatchedPlatforms = (match: MatchItem, index: number): PlatformKey[] => {
  const visibleMatched = (match.matchedPlatforms || []).filter(isVisiblePlatform)
  if (visibleMatched.length) return visibleMatched
  if (match.sourceCount >= 2) return ['pinnacle', 'crown']
  return fallbackPlatformSets[index % fallbackPlatformSets.length].slice(0, 1)
}

const buildChartPoints = (timestamps: number[], values: number[]) =>
  values.map((value, index) => [timestamps[index], value])

const getChartXRange = (timestamps: number[]) => {
  const values = timestamps.filter((value) => Number.isFinite(value))
  if (values.length === 0) return {}
  const minValue = Math.min(...values)
  const maxValue = Math.max(...values)
  if (minValue === maxValue) {
    const padding = 5 * 60 * 1000
    return { min: minValue - padding, max: maxValue + padding }
  }
  return { min: minValue, max: maxValue }
}

const getChartYRange = (series: MarketSeries[]) => {
  const values = series.flatMap((item) =>
    item.values.filter((value) => typeof value === 'number' && Number.isFinite(value)),
  )
  if (values.length === 0) return {}
  const minValue = Math.min(...values)
  const maxValue = Math.max(...values)
  const spread = maxValue - minValue
  const padding = spread > 0 ? spread * 0.2 : Math.max(Math.abs(maxValue) * 0.05, 0.05)
  return {
    min: Math.max(0, Number((minValue - padding).toFixed(3))),
    max: Number((maxValue + padding).toFixed(3)),
  }
}

const scheduleChartResize = (chart: echarts.ECharts) => {
  requestAnimationFrame(() => chart.resize())
  window.setTimeout(() => chart.resize(), 120)
  window.setTimeout(() => chart.resize(), 320)
}

const createSeriesWithLatestLabel = (
  series: MarketSeries,
  timestamps: number[],
  seriesIndex: number,
  seriesCount: number,
) => {
  const lastIndex = series.values.length - 1
  const lastValue = series.values[lastIndex]
  const labelOffset = (seriesIndex - (seriesCount - 1) / 2) * 0.018
  return {
    name: `${platformLabels[series.platform]} ${series.label}`,
    type: 'line',
    smooth: false,
    connectNulls: false,
    showSymbol: true,
    symbol: series.platform === 'crown' ? 'diamond' : 'circle',
    symbolSize: 4,
    color: platformColors[series.platform],
    data: buildChartPoints(timestamps, series.values),
    lineStyle: { width: 2 },
    emphasis: { focus: 'series' },
    markPoint: {
      symbolSize: 0,
      data: [{ coord: [timestamps[lastIndex], lastValue + labelOffset], value: series.latestText }],
      label: {
        show: true,
        formatter: '{c}',
        color: platformColors[series.platform],
        fontWeight: 700,
        fontSize: 12,
        backgroundColor: '#ffffff',
        borderColor: platformColors[series.platform],
        borderWidth: 1,
        borderRadius: 4,
        padding: [3, 6],
        position: 'right',
      },
    },
  }
}

const parseMetricLabel = (label: string) => {
  const [marketType = 'odds', selection = 'odds', ...lineParts] = label.split(' ')
  return {
    marketType,
    selection,
    line: lineParts.join(' ') || undefined,
  }
}

type CollectedMetricItem = {
  platform: PlatformKey
  marketType: string
  selection: string
  line?: string
  oddsValue: number
}

const buildCollectedMarketGroups = (
  selected: MatchDetail,
  history: MatchDetail['oddsHistory'],
): MarketGroup[] => {
  const items = selected.metrics.reduce<CollectedMetricItem[]>((result, metric) => {
    const oddsValue = Number(metric.value)
    if (!Number.isFinite(oddsValue)) return result
    if (!isVisiblePlatform(metric.sourceKey)) return result
    const parsed = parseMetricLabel(metric.label)
    result.push({
      platform: metric.sourceKey,
      marketType: parsed.marketType || 'odds',
      selection: selectionLabels[parsed.selection] || parsed.selection,
      line: parsed.line,
      oddsValue,
    })
    return result
  }, [])

  const groups = new Map<string, MarketGroup>()
  items.forEach((item) => {
    const group = groups.get(item.marketType) || {
      key: item.marketType,
      title: marketTitleLabels[item.marketType] || item.marketType,
      description: '按同一盘口线合并展示，缺少盘口的平台显示为无盘口。',
      platformKeys: [],
      rows: [],
      series: [],
    }
    const row = group.rows.find((target) => target.selection === item.selection && target.line === item.line)
    if (row) {
      row.odds[item.platform] = item.oddsValue
    } else {
      group.rows.push({ selection: item.selection, line: item.line, odds: { [item.platform]: item.oddsValue } })
    }
    if (!group.platformKeys.includes(item.platform)) group.platformKeys.push(item.platform)
    group.series.push({
      platform: item.platform,
      label: item.line ? `${item.selection} ${item.line}` : item.selection,
      values: history.map(() => toAsianOdd(item.oddsValue) ?? item.oddsValue),
      latestText: formatAsianOdd(item.oddsValue),
    })
    groups.set(item.marketType, group)
  })

  return Array.from(groups.values()).map((group) => ({
    ...group,
    platformKeys: visiblePlatformKeys.filter((platform) => group.platformKeys.includes(platform)),
    rows: group.rows.sort((left, right) =>
      `${left.line || ''}:${left.selection}`.localeCompare(`${right.line || ''}:${right.selection}`),
    ),
  }))
}

const buildMarketGroups = (history: MatchDetail['oddsHistory'], selected?: MatchDetail): MarketGroup[] => {
  if (selected?.metrics.length) {
    const collectedGroups = buildCollectedMarketGroups(selected, history)
    if (collectedGroups.length > 0) return collectedGroups
  }

  const pinnacle = history.map((item) => item.pinnacle)
  const crown = history.map((item) => item.crown)
  const toAsianSeries = (values: number[]) => values.map((value) => toAsianOdd(value) ?? value)
  const selectedPlatforms = selected?.match.matchedPlatforms?.length ? selected.match.matchedPlatforms : undefined

  const fallbackGroups: MarketGroup[] = [
    {
      key: 'handicap',
      title: '让球盘',
      description: '重复盘口合并显示，可按平台缺失情况自适应。',
      platformKeys: ['pinnacle', 'crown'],
      rows: [
        { selection: '主队', line: '-0.5', odds: { pinnacle: 2.31, crown: 2.28 } },
        { selection: '客队', line: '+0.5', odds: { pinnacle: 1.66, crown: 1.68 } },
        { selection: '主队', line: '-0.25', odds: { pinnacle: 1.94, crown: 1.96 } },
      ],
      series: [
        { platform: 'pinnacle', label: '主队 -0.5', values: toAsianSeries(pinnacle.map((value, index) => value + index * 0.002)), latestText: formatAsianOdd(pinnacle[pinnacle.length - 1] + 0.02) },
        { platform: 'crown', label: '主队 -0.5', values: toAsianSeries(crown.map((value, index) => value + 0.03 - index * 0.001)), latestText: formatAsianOdd(crown[crown.length - 1] + 0.03) },
      ],
    },
    {
      key: 'total',
      title: '大小球',
      description: '相同大小球盘口放在同一组，不同平台没有的盘口留空。',
      platformKeys: ['pinnacle', 'crown'],
      rows: [
        { selection: '大球', line: '2.5', odds: { pinnacle: 2.99, crown: 2.94 } },
        { selection: '小球', line: '2.5', odds: { pinnacle: 1.24, crown: 1.27 } },
        { selection: '大球', line: '2.75', odds: { pinnacle: 2.45, crown: 2.48 } },
      ],
      series: [
        { platform: 'pinnacle', label: '大球 2.5', values: toAsianSeries(pinnacle.map((value, index) => value + 0.7 + index * 0.006)), latestText: formatAsianOdd(pinnacle[pinnacle.length - 1] + 0.76) },
        { platform: 'crown', label: '大球 2.5', values: toAsianSeries(crown.map((value, index) => value + 0.72 + index * 0.004)), latestText: formatAsianOdd(crown[crown.length - 1] + 0.76) },
      ],
    },
    {
      key: 'winner',
      title: '胜平负',
      description: '三项盘口按主胜、平局、客胜合并比较。',
      platformKeys: ['pinnacle', 'crown'],
      rows: [
        { selection: '主胜', odds: { pinnacle: 1.92, crown: 1.89 } },
        { selection: '平局', odds: { pinnacle: 3.45, crown: 3.52 } },
        { selection: '客胜', odds: { pinnacle: 4.1, crown: 4.0 } },
      ],
      series: [
        { platform: 'pinnacle', label: '主胜', values: toAsianSeries(pinnacle.map((value, index) => value + 0.09 + index * 0.003)), latestText: formatAsianOdd(pinnacle[pinnacle.length - 1] + 0.12) },
        { platform: 'crown', label: '主胜', values: toAsianSeries(crown.map((value, index) => value + 0.1 + index * 0.004)), latestText: formatAsianOdd(crown[crown.length - 1] + 0.14) },
      ],
    },
  ]

  if (!selectedPlatforms?.length) return fallbackGroups
  return fallbackGroups
    .map((group) => ({
      ...group,
      platformKeys: group.platformKeys.filter((platform) => selectedPlatforms.includes(platform)),
      rows: group.rows.map((row) => ({
        ...row,
        odds: Object.fromEntries(
          Object.entries(row.odds).filter(([platform]) => selectedPlatforms.includes(platform as PlatformKey)),
        ) as Partial<Record<PlatformKey, number>>,
      })),
      series: group.series.filter((series) => selectedPlatforms.includes(series.platform)),
    }))
    .filter((group) => group.platformKeys.length > 0)
}

const OddsMonitor = () => {
  const [loading, setLoading] = useState(true)
  const [data, setData] = useState<DashboardData | null>(null)
  const [selectedDetail, setSelectedDetail] = useState<MatchDetail | null>(null)
  const [activeMatchViewKey, setActiveMatchViewKey] = useState<string | null>(null)
  const [activeMarketKeys, setActiveMarketKeys] = useState<string[]>(['handicap'])
  const [matchSearchQuery, setMatchSearchQuery] = useState('')
  const [matchFilter, setMatchFilter] = useState<MatchFilterKey>('all')
  const chartRefs = useRef<Record<string, HTMLDivElement | null>>({})
  const chartInstances = useRef<Record<string, echarts.ECharts>>({})
  const lastAutoJumpQuery = useRef('')

  useEffect(() => {
    apiClient.post<ApiResponse<DashboardData>>('/odds-monitor/dashboard', {})
      .then((response) => {
        setData(response.data.data)
        setSelectedDetail(response.data.data.selectedMatch || null)
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
      if (matchFilter === 'both') return platforms.length === 2
      if (matchFilter === 'pinnacle-crown') return platforms.includes('pinnacle') && platforms.includes('crown')
      if (matchFilter === 'single') return platforms.length === 1
      if (matchFilter === 'match-risk') return platforms.length === 1
      if (matchFilter === 'market-risk') return platforms.length > 0 && platforms.length < 2
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
    }, { pinnacle: 0, crown: 0 } as Record<PlatformKey, number>)
    return {
      ...platformCounts,
      both: matches.filter((match) => (match.matchedPlatforms || []).length === 2).length,
      isolated: matches.filter((match) => (match.matchedPlatforms || []).length === 1).length,
      marketRisk: matches.filter((match) => {
        const count = (match.matchedPlatforms || []).length
        return count > 0 && count < 2
      }).length,
    }
  }, [matches])

  const groupedMatches = useMemo(() => {
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
    apiClient.post<ApiResponse<MatchDetail | null>>('/odds-monitor/match-detail', { matchId: activeMatch.id })
      .then((response) => {
        if (!cancelled && response.data.data) setSelectedDetail(response.data.data)
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
  const chartTimestamps = chartHistory.map((item) => item.timestamp)
  const marketGroups = useMemo(
    () => (localizedSelected ? buildMarketGroups(chartHistory, localizedSelected) : []),
    [chartHistory, localizedSelected],
  )

  const chartOptions = useMemo(() => {
    const options: Record<string, echarts.EChartsCoreOption> = {}
    marketGroups.forEach((group) => {
      const xRange = getChartXRange(chartTimestamps)
      const yRange = getChartYRange(group.series)
      options[group.key] = {
        animation: false,
        backgroundColor: 'transparent',
        color: group.series.map((item) => platformColors[item.platform]),
        tooltip: {
          trigger: 'axis',
          backgroundColor: '#ffffff',
          borderColor: '#d6dee8',
          textStyle: { color: '#152033' },
        },
        legend: { top: 0, right: 16, textStyle: { color: '#5f6f86' } },
        grid: { left: 46, right: 102, top: 48, bottom: 34 },
        xAxis: {
          type: 'time',
          min: xRange.min,
          max: xRange.max,
          boundaryGap: false,
          axisLine: { lineStyle: { color: '#cbd5e1' } },
          axisLabel: { color: '#64748b', formatter: formatAxisTime },
          splitLine: { show: false },
        },
        yAxis: {
          type: 'value',
          scale: true,
          min: yRange.min,
          max: yRange.max,
          splitLine: { lineStyle: { color: '#e5eaf1', type: 'dashed' } },
          axisLabel: { color: '#64748b', formatter: (value: number) => value.toFixed(3) },
        },
        dataZoom: [{ type: 'inside', xAxisIndex: 0, filterMode: 'none' }],
        series: group.series.map((series, index) =>
          createSeriesWithLatestLabel(series, chartTimestamps, index, group.series.length),
        ),
      }
    })
    return options
  }, [chartTimestamps, marketGroups])

  const renderChartIntoElement = (key: string, element: HTMLDivElement | null) => {
    const option = chartOptions[key]
    if (!element || !option) return
    const chart = chartInstances.current[key] || echarts.init(element, undefined, {
      renderer: 'canvas',
      useDirtyRect: true,
    })
    chartInstances.current[key] = chart
    chart.setOption(option, { notMerge: true, lazyUpdate: true })
    scheduleChartResize(chart)
  }

  useEffect(() => {
    const activeKeys = new Set(activeMarketKeys)
    activeMarketKeys.forEach((key) => renderChartIntoElement(key, chartRefs.current[key]))
    Object.entries(chartInstances.current).forEach(([key, chart]) => {
      if (!activeKeys.has(key)) {
        chart.dispose()
        delete chartInstances.current[key]
      }
    })
  }, [activeMarketKeys, chartOptions])

  useEffect(() => {
    if (!('ResizeObserver' in window)) return undefined
    const observers = activeMarketKeys.map((key) => {
      const element = chartRefs.current[key]
      const chart = chartInstances.current[key]
      if (!element || !chart) return undefined
      const observer = new ResizeObserver(() => scheduleChartResize(chart))
      observer.observe(element)
      return observer
    })
    return () => observers.forEach((observer) => observer?.disconnect())
  }, [activeMarketKeys])

  useEffect(() => {
    const resize = () => Object.values(chartInstances.current).forEach((chart) => chart.resize())
    window.addEventListener('resize', resize)
    return () => {
      window.removeEventListener('resize', resize)
      Object.values(chartInstances.current).forEach((chart) => chart.dispose())
      chartInstances.current = {}
    }
  }, [])

  if (loading) return <Spin />
  if (!data || !localizedSelected || !activeMatch) return <Empty description="暂无比赛数据" />

  const renderMatchItem = (item: OddsMonitorPlatformMatchView) => (
    <List.Item
      className={item.viewKey === activeMatch.viewKey ? 'match-item active' : 'match-item'}
      onClick={() => setActiveMatchViewKey(item.viewKey)}
    >
      <div className="match-card-content">
        <div className="match-title-row">
          <span className={item.alertCount > 0 ? 'change-dot has-change' : 'change-dot'} />
          <Text strong className="match-title">{matchName(item)}</Text>
        </div>
        <Text type="secondary" className="match-meta">{formatDateTime(item.startTime)}</Text>
        <div className="platform-row">
          {item.matchedPlatforms?.map((platform) => (
            <span
              key={platform}
              className="platform-pill"
              style={{ borderColor: platformColors[platform], color: platformColors[platform] }}
            >
              {platformLabels[platform]}
            </span>
          ))}
        </div>
      </div>
    </List.Item>
  )

  return (
    <div className="odds-monitor-page light-mode">
      <header className="odds-monitor-topbar">
        <Space align="center" wrap>
          <span className="page-title">全平台赔率监控</span>
          <Tag color="blue">{matches.length} 场比赛</Tag>
          <Tag color="green">盘口折叠</Tag>
        </Space>
        <Space align="center" wrap className="topbar-actions">
          {['1小时', '2小时', '6小时', '全天'].map((label) => (
            <Button key={label} size="small" type={label === '全天' ? 'primary' : 'default'}>{label}</Button>
          ))}
          <Checkbox checked>自动刷新</Checkbox>
          <Text type="secondary">更新：{new Date().toLocaleString('zh-CN')}</Text>
        </Space>
      </header>

      <div className="odds-monitor-workspace">
        <aside className="match-panel">
          <div className="match-panel-header">
            <Text strong>比赛列表</Text>
            <Text type="secondary">
              {matchSearchQuery.trim()
                ? `${filteredMatches.length} / ${matchViews.length} 项`
                : `${matchViews.length} 项`}
            </Text>
          </div>
          <div className="match-search-bar">
            <Input.Search
              allowClear
              value={matchSearchQuery}
              placeholder="搜索比赛、联赛或平台"
              enterButton="跳转"
              onChange={(event) => setMatchSearchQuery(event.target.value)}
              onSearch={(value) => {
                const jumpMatch = getSearchJumpMatch(matchViews, value)
                if (jumpMatch) {
                  lastAutoJumpQuery.current = value
                  setActiveMatchViewKey(jumpMatch.viewKey)
                }
              }}
            />
            <div className="match-filter-row">
              {(Object.keys(matchFilterLabels) as MatchFilterKey[]).map((filterKey) => (
                <Button
                  key={filterKey}
                  size="small"
                  type={matchFilter === filterKey ? 'primary' : 'default'}
                  onClick={() => setMatchFilter(filterKey)}
                >
                  {matchFilterLabels[filterKey]}
                </Button>
              ))}
            </div>
          </div>
          {filteredMatches.length > 0 ? (
            groupedMatches.map((group) => (
              <div className="league-group" key={group.leagueName}>
                <div className="league-heading">
                  <Text strong>{group.leagueName}</Text>
                  <Text type="secondary">{group.matches.length} 场</Text>
                </div>
                <List dataSource={group.matches} renderItem={renderMatchItem} />
              </div>
            ))
          ) : (
            <Empty className="match-search-empty" description="没有匹配比赛" />
          )}
        </aside>

        <main className="monitor-main">
          <section className="match-heading">
            <div>
              <Title level={2}>{matchName(localizedSelected.match)}</Title>
              <Text type="secondary">
                {localizedSelected.match.leagueName} · {formatDateTime(localizedSelected.match.startTime)} · {localizedSelected.match.status}
              </Text>
            </div>
            <div className="source-legend">
              {(localizedSelected.match.matchedPlatforms || []).map((platform) => (
                <span key={platform}>
                  <i style={{ background: platformColors[platform] }} />
                  {platformLabels[platform]}
                </span>
              ))}
            </div>
          </section>

          <section className="status-strip">
            <span>平博 {statusCounts.pinnacle}</span>
            <span>皇冠 {statusCounts.crown}</span>
            <span>双平台 {statusCounts.both}</span>
            <span>单平台孤立 {statusCounts.isolated}</span>
            <span>疑似盘口缺失 {statusCounts.marketRisk}</span>
          </section>

          <section className="market-section">
            <div className="section-heading">
              <div>
                <Title level={4}>盘口与赔率波动</Title>
                <Text type="secondary">左侧按平台拆成独立页面；当前页面只显示所选平台盘口。</Text>
              </div>
              <Text type="secondary">最新赔率显示在走势图最右侧</Text>
            </div>

            <Collapse
              accordion
              activeKey={activeMarketKeys}
              onChange={(keys) => {
                if (Array.isArray(keys)) {
                  setActiveMarketKeys(keys.map(String))
                  return
                }
                setActiveMarketKeys(keys ? [String(keys)] : [])
              }}
              items={marketGroups.map((group) => ({
                key: group.key,
                label: (
                  <div className="market-collapse-title">
                    <span>{group.title}</span>
                    <Text type="secondary">{group.rows.length} 个盘口</Text>
                    <div className="market-platforms">
                      {group.platformKeys.map((platform) => (
                        <span key={platform} style={{ color: platformColors[platform] }}>{platformLabels[platform]}</span>
                      ))}
                    </div>
                  </div>
                ),
                children: (
                  <div className="market-panel-body">
                    <Text type="secondary">{group.description}</Text>
                    <div className="market-table">
                      <div className="market-table-head">
                        <span>盘口</span>
                        <span>选项</span>
                        {group.platformKeys.map((platform) => (
                          <span key={platform}>{platformLabels[platform]}</span>
                        ))}
                      </div>
                      {group.rows.map((row) => (
                        <div className="market-table-row" key={`${row.selection}-${row.line || 'none'}`}>
                          <span>{row.line || '-'}</span>
                          <strong>{row.selection}</strong>
                          {group.platformKeys.map((platform) => (
                            <span key={platform} className={platform}>
                              {row.odds[platform] === undefined
                                ? '无盘口'
                                : formatAsianOdd(row.odds[platform])}
                            </span>
                          ))}
                        </div>
                      ))}
                    </div>
                    <div
                      className="market-chart"
                      ref={(element) => {
                        chartRefs.current[group.key] = element
                        if (activeMarketKeys.includes(group.key)) {
                          window.setTimeout(() => renderChartIntoElement(group.key, element), 0)
                        }
                      }}
                    />
                  </div>
                ),
              }))}
            />
          </section>
        </main>
      </div>
    </div>
  )
}

export default OddsMonitor
