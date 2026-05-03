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
]

const mojibakeMarkers = ['鍏', '鏁', '鍛', '杩', '鐧', '瀵', '璐', '妯', '绫', '绾', '鏃', '搴']

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
      '数据源设置',
      '数据源状态',
      '告警记录',
      '运行日志',
      '免密登录失败',
    ].forEach((label) => expect(combined).toContain(label))
  })
})
