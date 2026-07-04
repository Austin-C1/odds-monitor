import type { EChartsCoreOption, ECharts } from 'echarts'
import { formatAxisTime, platformColors, platformLabels } from './labels'
import type { MarketGroup, MarketSeries } from './types'

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

export const scheduleChartResize = (chart: ECharts) => {
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

export const createMarketChartOptions = (
  marketGroups: MarketGroup[],
  chartTimestamps: number[],
): Record<string, EChartsCoreOption> => {
  const options: Record<string, EChartsCoreOption> = {}
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
}
