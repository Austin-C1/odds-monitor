import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const activeFiles = [
  'src/components/Layout.tsx',
  'src/pages/Login.tsx',
  'src/pages/ResetPassword.tsx',
  'src/pages/DataSourceSettings.tsx',
  'src/pages/DataSourceStatus.tsx',
  'src/pages/AlertRecords.tsx',
  'src/pages/RuntimeLogs.tsx',
  'src/pages/NotificationSettingsPage.tsx',
  'src/pages/LeagueFilter.tsx',
  'src/pages/DefaultTracking.tsx',
  'src/pages/PinnacleLeagueFilter.tsx',
  'src/pages/CrownLeagueFilter.tsx',
]

const mojibakeMarkers = ['闁', '閺', '閻', '婵', '缂', '閹', '鐠', '婢', '濠', '缁', '鑱', '绛涢', '鍏ㄥ', '姣旇']

describe('active page Chinese text', () => {
  it('does not contain mojibake in active odds-monitor pages', () => {
    const combined = activeFiles
      .map((file) => readFileSync(join(process.cwd(), file), 'utf8'))
      .join('\n')

    mojibakeMarkers.forEach((marker) => {
      expect(combined).not.toContain(marker)
    })
  })

  it('keeps visible labels readable', () => {
    const combined = activeFiles
      .map((file) => readFileSync(join(process.cwd(), file), 'utf8'))
      .join('\n')

    ;[
      '全平台赔率监控',
      '比赛监控',
      '默认追踪',
      '联赛筛选',
      '平博比赛选择',
      '皇冠比赛选择',
      '数据源设置',
      '数据源状态',
      '告警记录',
      '运行日志',
      '水位限制',
      '动水筛选',
      '免密登录失败',
    ].forEach((label) => expect(combined).toContain(label))
  })
})
