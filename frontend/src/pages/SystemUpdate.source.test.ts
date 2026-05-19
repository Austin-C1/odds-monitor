import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

describe('system update source', () => {
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
