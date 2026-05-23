import { describe, expect, it } from 'vitest'
import fs from 'node:fs'

describe('runtime logs source', () => {
  const source = fs.readFileSync('src/pages/RuntimeLogs.tsx', 'utf8')

  it('shows failure classification and collection counters', () => {
    expect(source).toContain('failureReason')
    expect(source).toContain('错误分类')
    expect(source).toContain('比赛')
    expect(source).toContain('盘口')
    expect(source).toContain('空盘口')
  })
})
