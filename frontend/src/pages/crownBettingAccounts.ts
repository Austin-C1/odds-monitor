import type { CrownAccount } from './crownBettingTypes'

export const CROWN_BETTING_ACCOUNTS_STORAGE_KEY = 'crown-betting-accounts'
export const DEFAULT_CROWN_LOGIN_URL = (import.meta.env.VITE_CROWN_LOGIN_URL || '').trim()
const LEGACY_SEEDED_ACCOUNT_PREFIX = 'crown-seed-'

export const resolveCrownLoginUrl = (loginUrl?: string | null) => loginUrl?.trim() || DEFAULT_CROWN_LOGIN_URL

export const readStoredAccounts = (): CrownAccount[] => {
  try {
    const raw = localStorage.getItem(CROWN_BETTING_ACCOUNTS_STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) as CrownAccount[] : []
    const storedAccounts = Array.isArray(parsed) ? parsed : []
    const accounts = storedAccounts
      .filter((item) => (
        item &&
        item.id &&
        item.id !== 'crown-data-source-account' &&
        !(String(item.id).startsWith(LEGACY_SEEDED_ACCOUNT_PREFIX) && item.bettingEnabled !== true) &&
        item.displayName &&
        item.loginName
      ))
      .map((item) => ({
        ...item,
        loginUrl: resolveCrownLoginUrl(item.loginUrl),
        adsPowerProfileId: item.adsPowerProfileId || '',
        adsPowerStatus: item.adsPowerStatus || (item.adsPowerProfileId ? 'unlinked' : undefined),
        adsPowerMessage: item.adsPowerMessage || undefined,
        adsPowerUpdatedAt: typeof item.adsPowerUpdatedAt === 'number' ? item.adsPowerUpdatedAt : undefined,
        bettingEnabled: typeof item.bettingEnabled === 'boolean' ? item.bettingEnabled : false,
        status: item.adsPowerProfileId && ['checking', 'error'].includes(item.status) ? 'unchecked' : (item.status || 'unchecked'),
        balance: typeof item.balance === 'number' ? item.balance : null,
        currency: item.currency || 'CNY',
        lastCheckedAt: typeof item.lastCheckedAt === 'number' ? item.lastCheckedAt : 0,
      }))
    if (accounts.length !== storedAccounts.length) {
      localStorage.setItem(CROWN_BETTING_ACCOUNTS_STORAGE_KEY, JSON.stringify(accounts))
    }
    return accounts
  } catch {
    localStorage.setItem(CROWN_BETTING_ACCOUNTS_STORAGE_KEY, JSON.stringify([]))
    return []
  }
}

export const persistStoredAccounts = (accounts: CrownAccount[]) => {
  localStorage.setItem(CROWN_BETTING_ACCOUNTS_STORAGE_KEY, JSON.stringify(accounts))
}

export const hasAdsPowerProfile = (account: Pick<CrownAccount, 'adsPowerProfileId'>) => Boolean(account.adsPowerProfileId?.trim())
export const isBettingEnabledAccount = (account: CrownAccount) => account.bettingEnabled === true

export const toExecutionAccounts = (accounts: CrownAccount[]) => accounts.map((account) => ({
  id: account.id,
  displayName: account.displayName,
  status: account.status,
  adsPowerProfileId: account.adsPowerProfileId,
  adsPowerStatus: account.adsPowerStatus,
  bettingEnabled: account.bettingEnabled === true,
}))
