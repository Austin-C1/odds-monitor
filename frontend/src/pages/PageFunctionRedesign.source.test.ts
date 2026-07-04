import { describe, expect, it } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const pageSource = (name: string) => readFileSync(join(process.cwd(), 'src', 'pages', name), 'utf8')

describe('page function redesign source', () => {
  it('provides shared operational page shell styles', () => {
    expect(existsSync(join(process.cwd(), 'src', 'pages', 'PageShell.tsx'))).toBe(true)
    expect(existsSync(join(process.cwd(), 'src', 'pages', 'PageShell.css'))).toBe(true)

    const shellSource = pageSource('PageShell.tsx')
    const shellCss = pageSource('PageShell.css')

    expect(shellSource).toContain('PageShell')
    expect(shellSource).toContain('page-shell')
    expect(shellCss).toContain('.page-shell')
    expect(shellCss).toContain('.page-stat-grid')
    expect(shellCss).toContain('.page-toolbar')
  })

  it('keeps each routed page visually independent through PageShell', () => {
    [
      'LeagueFilter.tsx',
      'CrownBetting.tsx',
      'BettingHistory.tsx',
      'DataSourceSettings.tsx',
      'DataSourceStatus.tsx',
      'AlertRecords.tsx',
      'NotificationSettingsPage.tsx',
      'RuntimeLogs.tsx',
      'SystemUpdate.tsx',
    ].forEach((name) => {
      const source = pageSource(name)
      expect(source, name).toContain("from './PageShell'")
      expect(source, name).toContain('<PageShell')
    })

    expect(pageSource('OddsMonitor.tsx')).toContain('odds-monitor-page')
  })

  it('renders league selection pages with separate page purposes', () => {
    const defaultTracking = pageSource('DefaultTracking.tsx')
    const crownLeagueFilter = pageSource('CrownLeagueFilter.tsx')
    const leagueFilter = pageSource('LeagueFilter.tsx')

    expect(defaultTracking).toContain('默认追踪')
    expect(defaultTracking).toContain('系统默认名单和皇冠比赛选择')
    expect(crownLeagueFilter).toContain('皇冠比赛选择')
    expect(crownLeagueFilter).toContain('皇冠抓到的原始联赛名')
    expect(leagueFilter).toContain('page-league-grid')
    expect(leagueFilter).toContain('page-toolbar')
  })
})
