const AUTOMATION_LOCK_KEY = 'crown-betting-automation-lock'

type AutomationLock = {
  owner: string
  expiresAt: number
}

const readLock = (): AutomationLock | null => {
  try {
    const raw = window.localStorage.getItem(AUTOMATION_LOCK_KEY)
    return raw ? JSON.parse(raw) as AutomationLock : null
  } catch {
    return null
  }
}

export const acquireCrownBettingAutomationLock = (
  owner: string,
  ttlMs = 180000,
  now = Date.now(),
): boolean => {
  try {
    const existing = readLock()
    if (existing?.owner && existing.owner !== owner && existing.expiresAt > now) {
      return false
    }
    window.localStorage.setItem(AUTOMATION_LOCK_KEY, JSON.stringify({
      owner,
      expiresAt: now + ttlMs,
    }))
    return readLock()?.owner === owner
  } catch {
    return true
  }
}

export const releaseCrownBettingAutomationLock = (owner: string): void => {
  try {
    if (readLock()?.owner === owner) {
      window.localStorage.removeItem(AUTOMATION_LOCK_KEY)
    }
  } catch {
    // Ignore storage failures; the lock has a short expiry.
  }
}
