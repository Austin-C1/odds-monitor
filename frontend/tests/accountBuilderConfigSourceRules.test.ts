import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function readSource(relativePath: string): string {
  return fs.readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8')
}

describe('account builder config source rules', () => {
  it('system settings should no longer render global builder or auto redeem forms', () => {
    const systemSettings = readSource('../src/pages/SystemSettings.tsx')

    expect(systemSettings).not.toContain('form={relayerForm}')
    expect(systemSettings).not.toContain('form={autoRedeemForm}')
    expect(systemSettings).not.toContain('builderApiKey.title')
    expect(systemSettings).not.toContain('systemSettings.autoRedeem')
  })

  it('account list should provide expandable per-account builder configuration editing', () => {
    const accountList = readSource('../src/pages/AccountList.tsx')

    expect(accountList).toContain('expandedRowRender')
    expect(accountList).toContain('AccountBuilderConfigCard')
  })

  it('account detail should render the account builder configuration card', () => {
    const accountDetail = readSource('../src/pages/AccountDetail.tsx')

    expect(accountDetail).toContain('AccountBuilderConfigCard')
    expect(accountDetail).toContain('autoRedeemEnabled')
  })
})
