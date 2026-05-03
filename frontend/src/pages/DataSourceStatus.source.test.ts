import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

describe('data source status source', () => {
  const source = readFileSync(join(process.cwd(), 'src', 'pages', 'DataSourceStatus.tsx'), 'utf8')

  it('maps backend collector status codes into readable labels', () => {
    expect(source).toContain('statusLabels')
    expect(source).toContain('success')
    expect(source).toContain('采集成功')
    expect(source).toContain('failed_login')
    expect(source).toContain('登录失败')
  })
})
