import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

describe('system update source', () => {
  it('falls back to the packaged build version when the backend version request fails', () => {
    const source = readFileSync(join(process.cwd(), 'src', 'pages', 'SystemUpdate.tsx'), 'utf8')

    expect(source).toContain("import { getVersionText } from '../utils'")
    expect(source).toContain('useState(getVersionText())')
    expect(source).toContain("response.data.data.version || getVersionText()")
    expect(source).not.toContain("currentVersion || 'unknown'")
  })

  it('exposes manual odds monitor history cleanup controls', () => {
    const source = readFileSync(join(process.cwd(), 'src', 'pages', 'SystemUpdate.tsx'), 'utf8')

    expect(source).toContain('/update/cleanup')
    expect(source).toContain('handleRunCleanup')
    expect(source).toContain('cleanupResult')
    expect(source).toContain('deletedSnapshots')
    expect(source).toContain('deletedCollectionLogs')
    expect(source).toContain('deletedBrokenAlertRecords')
    expect(source).toContain('历史数据大清理')
    expect(source).toContain('只清理旧赔率快照、旧采集日志和坏历史告警')
  })
})
