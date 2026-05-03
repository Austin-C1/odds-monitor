import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getToken, hasToken, removeToken } from '../src/utils/auth'

function buildJwt(expSeconds: number): string {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url')
  const payload = Buffer.from(JSON.stringify({ exp: expSeconds })).toString('base64url')
  return `${header}.${payload}.signature`
}

describe('auth token helpers', () => {
  const storage = new Map<string, string>()

  beforeEach(() => {
    storage.clear()
    vi.stubGlobal('localStorage', {
      getItem(key: string) {
        return storage.get(key) ?? null
      },
      setItem(key: string, value: string) {
        storage.set(key, value)
      },
      removeItem(key: string) {
        storage.delete(key)
      }
    } as Storage)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keeps a non-expired token available', () => {
    storage.set('jwt_token', buildJwt(Math.floor(Date.now() / 1000) + 3600))

    expect(getToken()).not.toBeNull()
    expect(hasToken()).toBe(true)
  })

  it('clears expired tokens before the app can keep using them', () => {
    storage.set('jwt_token', buildJwt(Math.floor(Date.now() / 1000) - 60))

    expect(getToken()).toBeNull()
    expect(hasToken()).toBe(false)
    expect(storage.has('jwt_token')).toBe(false)
  })

  it('removeToken clears the stored value', () => {
    storage.set('jwt_token', buildJwt(Math.floor(Date.now() / 1000) + 3600))

    removeToken()

    expect(getToken()).toBeNull()
    expect(hasToken()).toBe(false)
  })
})
