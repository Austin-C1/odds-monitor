import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

describe('odds monitor dashboard source', () => {
  const source = readFileSync(join(process.cwd(), 'src', 'pages', 'OddsMonitor.tsx'), 'utf8')
  const styles = readFileSync(join(process.cwd(), 'src', 'pages', 'OddsMonitor.css'), 'utf8')

  it('uses a light monitoring surface with platform badges in the match list', () => {
    expect(source).toContain('odds-monitor-page light-mode')
    expect(source).toContain('matchedPlatforms')
    expect(source).toContain('groupedMatches')
    expect(source).toContain('league-group')
    expect(source).toContain('league-heading')
    expect(source).toContain('platform-row')
    expect(source).toContain('platformLabels[platform]')
  })

  it('keeps visible monitor labels in Chinese', () => {
    ;[
      '全平台赔率监控',
      '比赛列表',
      '搜索比赛、联赛或平台',
      '让球盘',
      '大小球',
      '胜平负',
      '无盘口',
      '最新赔率显示在走势图最右侧',
      '三平台都有',
      '疑似盘口缺失',
      '平博',
      '皇冠',
    ].forEach((label) => expect(source).toContain(label))
    expect(source).not.toContain('Real Madrid')
    expect(source).not.toContain('Barcelona')
    expect(source).not.toContain('Arsenal')
    expect(source).not.toContain('Chelsea')
  })

  it('groups markets into collapsible panels instead of rendering every market at once', () => {
    expect(source).toContain('Collapse')
    expect(source).toContain('marketGroups')
    expect(source).toContain('buildCollectedMarketGroups')
    expect(source).toContain('parseMetricLabel')
    expect(source).toContain('activeMarketKeys')
    expect(source).toContain('market-collapse-title')
  })

  it('keeps the time controls locked above the monitor workspace and removes inactive metric cards', () => {
    expect(source).toContain('className="topbar-actions"')
    expect(source).not.toContain('summaryMetrics')
    expect(source).not.toContain('metric-strip')
    expect(source).not.toContain('metric-cell')
    expect(styles).toContain('overflow: hidden')
    expect(styles).toContain('flex: 1 1 auto')
    expect(styles).toContain('min-height: 0')
    expect(styles).not.toContain('.metric-strip')
    expect(styles).not.toContain('.metric-cell')
  })

  it('keeps the required platform chart colors and latest value labels', () => {
    expect(source).toContain("pinnacle: '#ef4444'")
    expect(source).toContain("crown: '#16a34a'")
    expect(source).toContain("polymarket: '#2563eb'")
    expect(source).toContain('createSeriesWithLatestLabel')
    expect(source).toContain('toAsianOdd')
    expect(source).toContain('formatAsianOdd')
    expect(source).toContain('formatAsianOdd(row.odds[platform])')
    expect(source).toContain('无盘口')
    expect(source).toContain('toAsianOdd(value)')
    expect(source).toContain('latestText')
  })

  it('uses real-time chart points, tight y-axis ranges, and non-smoothed odds lines', () => {
    expect(source).toContain('buildChartPoints')
    expect(source).toContain('getChartXRange')
    expect(source).toContain('getChartYRange')
    expect(source).toContain("type: 'time'")
    expect(source).toContain('min: xRange.min')
    expect(source).toContain('max: xRange.max')
    expect(source).toContain('dataZoom')
    expect(source).toContain('connectNulls: false')
    expect(source).toContain('smooth: false')
    expect(source).toContain('formatter: formatAxisTime')
  })

  it('resizes charts after collapse panels finish expanding to avoid squeezed lines', () => {
    expect(source).toContain('renderChartIntoElement')
    expect(source).toContain('scheduleChartResize')
    expect(source).toContain('ResizeObserver')
    expect(source).toContain('requestAnimationFrame')
    expect(source).toContain('window.setTimeout')
  })

  it('shows Polymarket盘口 as asian water while keeping the probability group as percentages', () => {
    expect(source).toContain('polymarketProbabilityToAsianOdd')
    expect(source).toContain('formatPolymarketAsianOdd')
    expect(source).toContain('formatPolymarketProbability')
    expect(source).toContain("platform === 'polymarket'")
    expect(source).toContain('formatPolymarketProbability(row.odds.polymarket)')
    expect(source).not.toContain('value * 3.8')
    expect(source).not.toContain('value * 5.1')
  })

  it('reuses chart instances instead of rebuilding lines on every page state change', () => {
    expect(source).toContain('chartInstances')
    expect(source).toContain('lazyUpdate: true')
    expect(source).toContain('useDirtyRect: true')
    expect(source).toContain('animation: false')
    expect(source).not.toContain('const charts: echarts.ECharts[] = []')
  })

  it('renders backend-collected crown markets instead of only simulated rows', () => {
    expect(source).toContain('selected.metrics')
    expect(source).toContain('metric.value')
    expect(source).toContain('marketTitleLabels')
    expect(source).toContain('selectionLabels')
    expect(source).toContain('matchedPlatforms?.[0]')
  })

  it('keeps one match entry and renders platform odds side by side', () => {
    expect(source).toContain('createPlatformMatchViews')
    expect(source).toContain('selectPlatformDetail')
    expect(source).toContain('activeMatchViewKey')
    expect(source).toContain('viewKey')
    expect(source).toContain('group.platformKeys.map((platform)')
    expect(source).toContain('platformLabels[platform]')
    expect(styles).not.toContain('.market-table.single-platform-table')
  })
})
