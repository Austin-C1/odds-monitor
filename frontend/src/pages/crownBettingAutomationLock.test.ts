import { beforeEach, describe, expect, it } from 'vitest'
import {
  acquireCrownBettingAutomationLock,
  releaseCrownBettingAutomationLock,
} from './crownBettingAutomationLock'

describe('crownBettingAutomationLock', () => {
  beforeEach(() => {
    const store = new Map<string, string>()
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: {
        getItem: (key: string) => store.get(key) ?? null,
        setItem: (key: string, value: string) => store.set(key, value),
        removeItem: (key: string) => store.delete(key),
        clear: () => store.clear(),
      },
    })
  })

  it('keeps the default lock active for a full crown execution window', () => {
    expect(acquireCrownBettingAutomationLock('first', undefined, 1_000)).toBe(true)

    expect(acquireCrownBettingAutomationLock('second', undefined, 121_000)).toBe(false)
    expect(acquireCrownBettingAutomationLock('second', undefined, 181_001)).toBe(true)
  })

  it('releases only the owner lock', () => {
    expect(acquireCrownBettingAutomationLock('first', undefined, 1_000)).toBe(true)

    releaseCrownBettingAutomationLock('second')
    expect(acquireCrownBettingAutomationLock('third', undefined, 2_000)).toBe(false)

    releaseCrownBettingAutomationLock('first')
    expect(acquireCrownBettingAutomationLock('third', undefined, 2_000)).toBe(true)
  })
})
