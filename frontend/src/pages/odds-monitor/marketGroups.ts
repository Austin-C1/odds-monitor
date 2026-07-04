import {
  formatAsianOdd,
  isVisiblePlatform,
  marketTitleLabels,
  selectionLabels,
  toAsianOdd,
  visiblePlatformKeys,
} from './labels'
import type { MatchDetail, MarketGroup, PlatformKey } from './types'

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

export const buildMarketGroups = (history: MatchDetail['oddsHistory'], selected?: MatchDetail): MarketGroup[] => {
  if (selected?.metrics.length) {
    const collectedGroups = buildCollectedMarketGroups(selected, history)
    if (collectedGroups.length > 0) return collectedGroups
  }

  const crown = history.map((item) => item.crown)
  const toAsianSeries = (values: number[]) => values.map((value) => toAsianOdd(value) ?? value)
  const selectedPlatforms = selected?.match.matchedPlatforms?.length ? selected.match.matchedPlatforms : undefined

  const fallbackGroups: MarketGroup[] = [
    {
      key: 'handicap',
      title: '让球盘',
      description: '重复盘口合并显示，可按平台缺失情况自适应。',
      platformKeys: ['crown'],
      rows: [
        { selection: '主队', line: '-0.5', odds: { crown: 2.28 } },
        { selection: '客队', line: '+0.5', odds: { crown: 1.68 } },
        { selection: '主队', line: '-0.25', odds: { crown: 1.96 } },
      ],
      series: [
        { platform: 'crown', label: '主队 -0.5', values: toAsianSeries(crown.map((value, index) => value + 0.03 - index * 0.001)), latestText: formatAsianOdd(crown[crown.length - 1] + 0.03) },
      ],
    },
    {
      key: 'total',
      title: '大小球',
      description: '相同大小球盘口放在同一组，不同平台没有的盘口留空。',
      platformKeys: ['crown'],
      rows: [
        { selection: '大球', line: '2.5', odds: { crown: 2.94 } },
        { selection: '小球', line: '2.5', odds: { crown: 1.27 } },
        { selection: '大球', line: '2.75', odds: { crown: 2.48 } },
      ],
      series: [
        { platform: 'crown', label: '大球 2.5', values: toAsianSeries(crown.map((value, index) => value + 0.72 + index * 0.004)), latestText: formatAsianOdd(crown[crown.length - 1] + 0.76) },
      ],
    },
    {
      key: 'winner',
      title: '胜平负',
      description: '三项盘口按主胜、平局、客胜合并比较。',
      platformKeys: ['crown'],
      rows: [
        { selection: '主胜', odds: { crown: 1.89 } },
        { selection: '平局', odds: { crown: 3.52 } },
        { selection: '客胜', odds: { crown: 4.0 } },
      ],
      series: [
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
