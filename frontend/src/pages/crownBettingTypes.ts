import type { AutoBettingMode } from './crownBettingExecutionPlan'

export type CrownAccountStatus = 'unchecked' | 'checking' | 'success' | 'error'
export type AdsPowerProfileStatus = 'unlinked' | 'starting' | 'opened' | 'closed' | 'error'

export type CrownAccount = {
  id: string
  displayName: string
  loginName: string
  loginUrl: string
  adsPowerProfileId?: string
  adsPowerStatus?: AdsPowerProfileStatus
  adsPowerMessage?: string
  adsPowerUpdatedAt?: number
  bettingEnabled?: boolean
  status: CrownAccountStatus
  balance: number | null
  currency: string
  lastCheckedAt: number
  note?: string
}

export type CrownAccountFormValues = {
  displayName: string
  loginName: string
  loginUrl: string
  adsPowerProfileId?: string
  note?: string
}

export type ApiResponse<T> = { code: number; data: T; msg?: string }

export type AdsPowerStatusResponse = {
  available: boolean
  baseUrl: string
  code?: number | null
  message: string
  checkedAt: number
}

export type AdsPowerStartProfileResponse = {
  profileId: string
  opened: boolean
  message: string
  debugPort?: string | null
  openedAt: number
}

export type AdsPowerCrownSessionResponse = {
  profileId: string
  opened: boolean
  loggedIn: boolean
  accountStatus: string
  balance?: number | null
  currency?: string | null
  pageUrl?: string | null
  message: string
  debugPort?: string | null
  checkedAt: number
}

export type AutoBettingDecisionResponse = {
  id?: number | null
  status: string
  reason: string
  dedupeKey?: string
  signalSource?: string
  bettingMode?: AutoBettingMode
  matchPhase?: AutoBettingMode
  accountKey: string
  leagueName?: string
  matchTitle?: string
  marketType?: string
  lineValue?: string | null
  selectionName?: string
  referenceSourceKey?: string
  targetSourceKey?: string
  referenceOdds?: number
  targetOdds?: number
  targetDecimalOdds?: number
  decimalEdge?: number
  stakeAmount?: number
  capturedAt?: number
  createdAt?: number
  crownHistoryVerified?: boolean
  crownHistoryCheckedAt?: number | null
  crownBetReference?: string | null
  queuePosition?: number | null
  queueTotal?: number | null
}

export type NotificationConfigResponse = {
  id?: number | null
  name?: string | null
  type?: string | null
  enabled?: boolean
  config?: {
    data?: {
      monitorModeEnabled?: boolean
      liveOnlyModeEnabled?: boolean
    }
    monitorModeEnabled?: boolean
    liveOnlyModeEnabled?: boolean
  }
}

export type SystemConfigResponse = {
  autoBettingEnabled?: boolean
}

export type AutomationSettings = {
  autoMode: AutoBettingMode
  autoEnabled: boolean
  perAccountLimit: number
  betLimit: number
  minimumBetOdds: number
  signalMaxAgeSeconds: number
}

export type CurrentExecutionStep = {
  accountName: string
  stageLabel: string
  phaseLabel: string
  signalTitle: string
  queueLabel: string
} | null
