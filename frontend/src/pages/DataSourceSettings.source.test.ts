import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

describe('data source settings source', () => {
  const source = readFileSync(join(process.cwd(), 'src', 'pages', 'DataSourceSettings.tsx'), 'utf8')

  it('lets crown store the platform URL used by the backend collector', () => {
    expect(source).toContain("sourceKey === 'crown'")
    expect(source).toContain("name={[field.name, 'queryKeyword']}")
    expect(source).toContain('平台网址')
    expect(source).toContain('https://hga038.com/')
  })

  it('describes that enabled sources are collected by the backend scheduler', () => {
    expect(source).toContain('启用后后端会按间隔自动采集')
  })
})
