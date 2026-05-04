import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(__dirname, '..')

describe('league filter page source', () => {
  it('adds league filter and default tracking routes and menu items', () => {
    const appSource = fs.readFileSync(path.join(root, 'App.tsx'), 'utf8')
    const layoutSource = fs.readFileSync(path.join(root, 'components', 'Layout.tsx'), 'utf8')

    expect(appSource).toContain('const LeagueFilter')
    expect(appSource).toContain('const DefaultTracking')
    expect(appSource).toContain('path="/league-filter"')
    expect(appSource).toContain('path="/default-tracking"')
    expect(layoutSource).toContain("key: '/league-filter'")
    expect(layoutSource).toContain("key: '/default-tracking'")
    expect(layoutSource).toContain("label: '联赛筛选'")
    expect(layoutSource).toContain("label: '默认追踪'")
  })

  it('league selector loads available leagues and saves selected names', () => {
    const pageSource = fs.readFileSync(path.join(root, 'pages', 'LeagueFilter.tsx'), 'utf8')

    expect(pageSource).toContain('/odds-monitor/leagues/list')
    expect(pageSource).toContain('/odds-monitor/leagues/save')
    expect(pageSource).toContain('Checkbox.Group')
    expect(pageSource).toContain('手动增加联赛')
    expect(pageSource).toContain('保存筛选')
  })

  it('league selector can search leagues locally', () => {
    const pageSource = fs.readFileSync(path.join(root, 'pages', 'LeagueFilter.tsx'), 'utf8')

    expect(pageSource).toContain('searchQuery')
    expect(pageSource).toContain('filteredLeagues')
    expect(pageSource).toContain('placeholder="搜索联赛"')
    expect(pageSource).toContain('搜索结果')
  })
})
