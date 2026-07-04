import { useEffect, useMemo, useRef, useState } from 'react'
import { Collapse, Typography } from 'antd'
import * as echarts from 'echarts'
import { createMarketChartOptions, scheduleChartResize } from './chartOptions'
import { formatAsianOdd, platformColors, platformLabels } from './labels'
import type { MarketGroup } from './types'

const { Title, Text } = Typography

type MarketCollapsePanelProps = {
  marketGroups: MarketGroup[]
  chartTimestamps: number[]
}

export const MarketCollapsePanel = ({ marketGroups, chartTimestamps }: MarketCollapsePanelProps) => {
  const [activeMarketKeys, setActiveMarketKeys] = useState<string[]>(['handicap'])
  const chartRefs = useRef<Record<string, HTMLDivElement | null>>({})
  const chartInstances = useRef<Record<string, echarts.ECharts>>({})

  const chartOptions = useMemo(
    () => createMarketChartOptions(marketGroups, chartTimestamps),
    [chartTimestamps, marketGroups],
  )

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

  return (
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
  )
}
