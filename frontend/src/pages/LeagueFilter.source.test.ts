import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(__dirname, '..')

describe('league filter page source', () => {
  it('adds a dedicated league filter route and menu item', () => {
    const appSource = fs.readFileSync(path.join(root, 'App.tsx'), 'utf8')
    const layoutSource = fs.readFileSync(path.join(root, 'components', 'Layout.tsx'), 'utf8')

    expect(appSource).toContain("const LeagueFilter")
    expect(appSource).toContain('path="/league-filter"')
    expect(layoutSource).toContain("key: '/league-filter'")
    expect(layoutSource).toContain("label: '联赛筛选'")
  })

  it('league filter page loads available leagues and saves selected names', () => {
    const pageSource = fs.readFileSync(path.join(root, 'pages', 'LeagueFilter.tsx'), 'utf8')

    expect(pageSource).toContain('/odds-monitor/leagues/list')
    expect(pageSource).toContain('/odds-monitor/leagues/save')
    expect(pageSource).toContain('Checkbox.Group')
    expect(pageSource).toContain('手动增加联赛')
    expect(pageSource).toContain('保存筛选')
  })
})
