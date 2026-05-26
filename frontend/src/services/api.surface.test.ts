import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

describe('frontend API surface', () => {
  it('keeps only current odds-monitor support APIs in the bundled service', () => {
    const source = readFileSync(join(process.cwd(), 'src/services/api.ts'), 'utf8')

    ;[
      '/accounts/',
      '/announcements/',
      '/backtest/',
      '/copy-trading/',
      '/crypto-tail-strategy/',
      '/markets/',
      '/system/large-bet-monitor/',
      '/system/proxy/',
      '/system/rpc-nodes/',
      '/system/users/',
      '/system/config/builder-api-key/',
      '/system/config/auto-redeem/',
    ].forEach((legacyEndpoint) => {
      expect(source).not.toContain(legacyEndpoint)
    })

    ;[
      '/auth/local-login',
      '/auth/reset-password',
      '/system/notifications/',
      '/system/config/live-observation-minutes/update',
      '/system/config/auto-betting-enabled/update',
    ].forEach((currentEndpoint) => {
      expect(source).toContain(currentEndpoint)
    })
  })

  it('uses the backend directly on the packaged local frontend to keep page serving independent from long API calls', () => {
    const source = readFileSync(join(process.cwd(), 'src/services/api.ts'), 'utf8')

    expect(source).toContain('http://127.0.0.1:18000')
    expect(source).toContain('isPackagedLocalFrontend')
    expect(source).toContain("'localhost'")
    expect(source).toContain("isPackagedLocalFrontend ? `${localBackendUrl}/api` : '/api'")
  })

  it('keeps development proxy aligned with the local backend port', () => {
    const source = readFileSync(join(process.cwd(), 'vite.config.ts'), 'utf8')

    expect(source).toContain("env.VITE_API_URL || 'http://127.0.0.1:18000'")
    expect(source).toContain("env.VITE_WS_URL || 'ws://127.0.0.1:18000'")
  })
})
