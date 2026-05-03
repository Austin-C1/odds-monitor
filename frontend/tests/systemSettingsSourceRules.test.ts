import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function readSource(relativePath: string): string {
  return fs.readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8')
}

describe('system settings source rules', () => {
  it('should remove global builder section and keep proxy password autofill disabled', () => {
    const systemSettings = readSource('../src/pages/SystemSettings.tsx')

    expect(systemSettings).not.toContain('form={relayerForm}')
    expect(systemSettings).not.toContain('form={autoRedeemForm}')
    expect(systemSettings).not.toContain('Builder API Key')
    expect(systemSettings).toContain('name="proxyPassword"')
    expect(systemSettings).toContain('autoComplete="new-password"')
    expect(systemSettings).toContain('Builder 与自动赎回已改到账号内配置')
  })
})
