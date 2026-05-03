import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

describe('app navigation', () => {
  it('exposes only the odds monitoring base navigation', () => {
    const appSource = readFileSync(join(process.cwd(), 'src', 'App.tsx'), 'utf8')
    const layoutSource = readFileSync(join(process.cwd(), 'src', 'components', 'Layout.tsx'), 'utf8')

    expect(appSource).toContain("import('./pages/OddsMonitor')")
    expect(appSource).toContain("import('./pages/DataSourceSettings')")
    expect(appSource).toContain("import('./pages/DataSourceStatus')")
    expect(appSource).toContain("import('./pages/AlertRecords')")
    expect(appSource).toContain("import('./pages/RuntimeLogs')")
    expect(appSource).toContain("import('./pages/MarketBettingQuery')")
    expect(appSource).toContain("import('./pages/SystemUpdate')")
    expect(appSource).toContain('path="/odds-monitor"')
    expect(appSource).toContain('path="/data-sources/settings"')
    expect(appSource).toContain('path="/data-sources/status"')
    expect(appSource).toContain('path="/alerts"')
    expect(appSource).toContain('path="/runtime-logs"')
    expect(appSource).toContain('path="/polymarket-query"')
    expect(appSource).toContain('path="/system-settings/update"')
    expect(layoutSource).toContain('全平台赔率监控')
    expect(layoutSource).toContain('比赛监控')
    expect(layoutSource).toContain('数据源设置')
    expect(layoutSource).toContain('数据源状态')
    expect(layoutSource).toContain('告警记录')
    expect(layoutSource).toContain('告警通知')
    expect(layoutSource).toContain('Polymarket 查询')
    expect(layoutSource).toContain('运行日志')
    expect(layoutSource).toContain('系统更新')
  })

  it('does not expose trading, positions, leaders, announcements, or rpc nodes', () => {
    const appSource = readFileSync(join(process.cwd(), 'src', 'App.tsx'), 'utf8')
    const layoutSource = readFileSync(join(process.cwd(), 'src', 'components', 'Layout.tsx'), 'utf8')
    const combined = `${appSource}\n${layoutSource}`

    expect(combined).not.toContain('/copy-trading')
    expect(combined).not.toContain('/leaders')
    expect(combined).not.toContain('/positions')
    expect(combined).not.toContain('/accounts')
    expect(combined).not.toContain('/announcements')
    expect(combined).not.toContain('/system-settings/rpc-nodes')
    expect(combined).not.toContain('Leader')
    expect(combined).not.toContain('跟单')
    expect(combined).not.toContain('仓位')
    expect(combined).not.toContain('交易')
  })

  it('uses passwordless local login page without username or password inputs', () => {
    const loginSource = readFileSync(join(process.cwd(), 'src', 'pages', 'Login.tsx'), 'utf8')

    expect(loginSource).toContain('localLogin')
    expect(loginSource).not.toContain('name="username"')
    expect(loginSource).not.toContain('name="password"')
    expect(loginSource).not.toContain('Input.Password')
    expect(loginSource).not.toContain('UserOutlined')
    expect(loginSource).not.toContain('LockOutlined')
  })
})
