import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function readSource(relativePath: string): string {
  return fs.readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8')
}

describe('position list source rules', () => {
  it('position list should avoid deprecated Button.Group and null Select values', () => {
    const positionList = readSource('../src/pages/PositionList.tsx')

    expect(positionList).not.toContain('<Button.Group>')
    expect(positionList).not.toContain('</Button.Group>')
    expect(positionList).not.toContain("{ value: null, label: '全部账户' }")
    expect(positionList).toContain('Space.Compact')
    expect(positionList).toContain('ALL_ACCOUNTS_OPTION')
    expect(positionList).toContain('Select<number | typeof ALL_ACCOUNTS_OPTION>')
    expect(positionList).toContain('onChange={(value: number | typeof ALL_ACCOUNTS_OPTION)')
  })
})
