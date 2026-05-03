import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { displayMarketTitle, displayOutcomeName, isMainGameMarketType } from './marketBettingDisplay'

describe('market betting query page', () => {
  const source = () => readFileSync(join(process.cwd(), 'src', 'pages', 'MarketBettingQuery.tsx'), 'utf8')

  it('preserves existing telegram routing fields when saving query bots', () => {
    const pageSource = source()

    expect(pageSource).toContain('copyTradingLeaderGroups: telegram.copyTradingLeaderGroups || []')
    expect(pageSource).toContain('copyTradingCategories: telegram.copyTradingCategories || []')
    expect(pageSource).toContain('copyTradingNotificationTypes: telegram.copyTradingNotificationTypes || []')
    expect(pageSource).toContain('handicapCombinedWaterMin: telegram.handicapCombinedWaterMin')
    expect(pageSource).toContain('totalCombinedWaterMin: telegram.totalCombinedWaterMin')
    expect(pageSource).toContain('marketBettingDailyReportEnabled: queryBotIds.includes(config.id!) && dailyReportEnabled')
    expect(pageSource).toContain('marketBettingDailyReportTime: dailyReportTime')
  })

  it('keeps market search usable when query bot loading fails', () => {
    const pageSource = source()

    expect(pageSource).toContain('botLoadFailed')
    expect(pageSource).toContain('setBotLoadFailed(true)')
    expect(pageSource).not.toContain("message.error('加载查询机器人失败')")
  })

  it('shows traded shares and traded amount without top holder links in market details', () => {
    const pageSource = source()

    expect(pageSource).toContain("title: '已成交 shares'")
    expect(pageSource).toContain("dataIndex: 'tradedAmount'")
    expect(pageSource).not.toContain('holder.profileUrl')
    expect(pageSource).not.toContain("title: 'Top 5 shares 持仓'")
  })

  it('sends selected event date when searching markets', () => {
    const pageSource = source()

    expect(pageSource).toContain('DatePicker')
    expect(pageSource).toContain('TimePicker')
    expect(pageSource).toContain('每日自动推送')
    expect(pageSource).toContain("values.date?.format('YYYY-MM-DD')")
    expect(pageSource).toContain('date })')
    expect(pageSource).toContain('marketLimit: 40, date')
    expect(pageSource).toContain('pagination={{ pageSize: 8 }}')
  })

  it('displays over under markets as Chinese big and small', () => {
    expect(displayMarketTitle('Luquentz Dort: Points O/U 2.5')).toBe('Luquentz Dort: Points 大小 2.5')
    expect(displayOutcomeName('Yes', 'Luquentz Dort: Points O/U 2.5', 'points')).toBe('大')
    expect(displayOutcomeName('No', 'Luquentz Dort: Points O/U 2.5', 'points')).toBe('小')
  })
  it('keeps only main game markets in the visible list', () => {
    expect(isMainGameMarketType('moneyline')).toBe(true)
    expect(isMainGameMarketType('spreads')).toBe(true)
    expect(isMainGameMarketType('totals')).toBe(true)
    expect(isMainGameMarketType('points')).toBe(false)
    expect(isMainGameMarketType('rebounds')).toBe(false)
    expect(isMainGameMarketType('assists')).toBe(false)
    expect(isMainGameMarketType('first_half_totals')).toBe(false)
  })
})
