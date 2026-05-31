import { type CSSProperties, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Button,
  Card,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Segmented,
  Space,
  Statistic,
  Switch,
  Tag,
  Typography,
  message,
} from 'antd'
import { ApiOutlined, PlusOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons'
import { apiClient } from '../services/api'
import { extractApiErrorMessage } from '../utils/apiError'
import {
  autoBettingSignalKey,
  buildCrownAlertSignalQueue,
  buildAutoBettingExecutionPlan,
  extractCrownAlertSignalCandidates,
  filterFreshCrownAlertSignals,
  formatAutoBettingReason,
  isCompletedDuplicateAutoBettingReason,
  selectNextCrownAlertSignal,
  type AutoBettingMode,
  type AutoBettingExecutionPlan,
  type QueuedCrownAlertSignal,
  type OddsAlertRecord,
} from './crownBettingExecutionPlan'
import {
  acquireCrownBettingAutomationLock,
  releaseCrownBettingAutomationLock,
} from './crownBettingAutomationLock'

const { Text, Title } = Typography

type CrownAccountStatus = 'unchecked' | 'checking' | 'success' | 'error'
type AdsPowerProfileStatus = 'unlinked' | 'starting' | 'opened' | 'closed' | 'error'

type CrownAccount = {
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

type CrownAccountFormValues = {
  displayName: string
  loginName: string
  loginUrl: string
  adsPowerProfileId?: string
  note?: string
}

type ApiResponse<T> = { code: number; data: T; msg?: string }
type AdsPowerStatusResponse = {
  available: boolean
  baseUrl: string
  code?: number | null
  message: string
  checkedAt: number
}
type AdsPowerStartProfileResponse = {
  profileId: string
  opened: boolean
  message: string
  debugPort?: string | null
  openedAt: number
}
type AdsPowerCrownSessionResponse = {
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
type AutoBettingDecisionResponse = {
  id?: number | null
  status: string
  reason: string
  dedupeKey: string
  signalSource: string
  bettingMode: AutoBettingMode
  matchPhase: AutoBettingMode
  accountKey: string
  leagueName: string
  matchTitle: string
  marketType: string
  lineValue?: string | null
  selectionName: string
  referenceSourceKey: string
  targetSourceKey: string
  referenceOdds: number
  targetOdds: number
  targetDecimalOdds: number
  decimalEdge: number
  stakeAmount: number
  capturedAt: number
  createdAt: number
  crownHistoryVerified?: boolean
  crownHistoryCheckedAt?: number | null
  crownBetReference?: string | null
  queuePosition?: number | null
  queueTotal?: number | null
}
type NotificationConfigResponse = {
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
type SystemConfigResponse = {
  autoBettingEnabled?: boolean
}

const STORAGE_KEY = 'crown-betting-accounts'
const AUTOMATION_SETTINGS_STORAGE_KEY = 'crown-betting-automation-settings'
const CROWN_LOGIN_URL = 'https://m407.mos077.com/'
const LEGACY_SEEDED_ACCOUNT_PREFIX = 'crown-seed-'
const AUTO_BETTING_POLL_INTERVAL_MS = 5000
const AUTO_BETTING_SIGNAL_RETRY_COOLDOWN_MS = AUTO_BETTING_POLL_INTERVAL_MS
const AUTO_BETTING_ACCOUNT_EXECUTION_TIMEOUT_MS = 30000

type AutomationSettings = {
  autoMode: AutoBettingMode
  autoEnabled: boolean
  perAccountLimit: number
  betLimit: number
  minimumBetOdds: number
  signalMaxAgeSeconds: number
}

type CurrentExecutionStep = {
  accountName: string
  stageLabel: string
  phaseLabel: string
  signalTitle: string
  queueLabel: string
} | null

const DEFAULT_AUTOMATION_SETTINGS: AutomationSettings = {
  autoMode: 'prematch',
  autoEnabled: false,
  perAccountLimit: 50,
  betLimit: 100,
  minimumBetOdds: 0.70,
  signalMaxAgeSeconds: 360,
}

const summaryGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(4, minmax(140px, 1fr))',
  gap: 12,
  maxWidth: 700,
  marginBottom: 16,
}

const accountGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(220px, 1.35fr) 136px 112px minmax(170px, 1fr) minmax(180px, 1fr) 184px',
  gap: 12,
  alignItems: 'center',
}

const accountRowStyle: CSSProperties = {
  padding: '8px 0',
  borderBottom: '1px solid #f0f0f0',
}

const compactAccountCardStyles = {
  header: { minHeight: 44, padding: '0 16px' },
  body: { padding: '10px 16px 12px' },
}

const valueBlockStyle: CSSProperties = {
  minWidth: 0,
}

const automationControlGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
  gap: 12,
  alignItems: 'end',
  marginBottom: 16,
}

const automationFieldStyle: CSSProperties = {
  minWidth: 0,
}

const automationSignalStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(220px, 1.3fr) repeat(4, minmax(120px, 1fr))',
  gap: 12,
  padding: '10px 0',
  borderTop: '1px solid #f0f0f0',
  borderBottom: '1px solid #f0f0f0',
  marginBottom: 12,
}

const executionStageStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
  gap: 12,
  padding: '10px 0',
  borderTop: '1px solid #f0f0f0',
  marginBottom: 12,
}

const signalCandidateGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '64px minmax(180px, 1.15fr) 92px minmax(140px, 1fr) minmax(110px, 0.8fr) minmax(120px, 0.8fr) minmax(190px, 1.2fr)',
  gap: 10,
  alignItems: 'center',
}

const signalCandidateRowStyle: CSSProperties = {
  padding: '8px 0',
  borderBottom: '1px solid #f0f0f0',
}

const executionResultGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(150px, 1.1fr) 96px 76px minmax(170px, 1.2fr) minmax(120px, 1fr) 90px 112px minmax(150px, 1fr)',
  gap: 10,
  alignItems: 'center',
}

const executionResultRowStyle: CSSProperties = {
  padding: '8px 0',
  borderBottom: '1px solid #f0f0f0',
}

const automationModeOptions = [
  { label: '赛前', value: 'prematch' },
  { label: '滚球', value: 'live' },
]

const statusMeta: Record<CrownAccountStatus, { label: string; color: string }> = {
  unchecked: { label: '待检测', color: 'default' },
  checking: { label: '检测中', color: 'processing' },
  success: { label: '在线', color: 'success' },
  error: { label: '异常', color: 'error' },
}

const formatCurrency = (value: number, currency = 'CNY') =>
  new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
  }).format(value)

const formatDateTime = (value: number) =>
  new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })

const normalizeNumberSetting = (
  value: unknown,
  fallback: number,
  min: number,
  max?: number,
) => {
  const numericValue = Number(value)
  if (!Number.isFinite(numericValue)) return fallback
  const lowerBoundedValue = Math.max(min, numericValue)
  return typeof max === 'number' ? Math.min(max, lowerBoundedValue) : lowerBoundedValue
}

const readStoredAutomationSettings = (): AutomationSettings => {
  try {
    const raw = localStorage.getItem(AUTOMATION_SETTINGS_STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) as Partial<AutomationSettings> : {}
    return {
      autoMode: parsed.autoMode === 'live' ? 'live' : 'prematch',
      autoEnabled: typeof parsed.autoEnabled === 'boolean' ? parsed.autoEnabled : DEFAULT_AUTOMATION_SETTINGS.autoEnabled,
      perAccountLimit: normalizeNumberSetting(
        parsed.perAccountLimit,
        DEFAULT_AUTOMATION_SETTINGS.perAccountLimit,
        50,
        500,
      ),
      betLimit: normalizeNumberSetting(parsed.betLimit, DEFAULT_AUTOMATION_SETTINGS.betLimit, 10),
      minimumBetOdds: normalizeNumberSetting(parsed.minimumBetOdds, DEFAULT_AUTOMATION_SETTINGS.minimumBetOdds, 0.01),
      signalMaxAgeSeconds: normalizeNumberSetting(
        parsed.signalMaxAgeSeconds,
        DEFAULT_AUTOMATION_SETTINGS.signalMaxAgeSeconds,
        1,
        3600,
      ),
    }
  } catch {
    return DEFAULT_AUTOMATION_SETTINGS
  }
}

const persistAutomationSettings = (settings: AutomationSettings) => {
  localStorage.setItem(AUTOMATION_SETTINGS_STORAGE_KEY, JSON.stringify(settings))
}

const updateAutoBettingEnabled = (autoBettingEnabled: boolean) => (
  apiClient.post<ApiResponse<SystemConfigResponse>>(
    '/system/config/auto-betting-enabled/update',
    { autoBettingEnabled },
  )
)

const monitorModeFromNotificationConfigs = (configs: NotificationConfigResponse[]): AutoBettingMode | null => {
  const monitorConfig = configs.find((config) => {
    if (config.enabled === false) return false
    const data = config.config?.data || config.config
    return data?.monitorModeEnabled === true
  })
  const data = monitorConfig?.config?.data || monitorConfig?.config
  if (!data || typeof data.liveOnlyModeEnabled !== 'boolean') {
    return null
  }
  return data.liveOnlyModeEnabled ? 'live' : 'prematch'
}

const formatBalanceState = (account: CrownAccount) => {
  if (typeof account.balance === 'number') {
    return formatCurrency(account.balance, account.currency)
  }
  return account.status === 'success' ? '余额未返回' : '待检测'
}

const accountCheckSummary = (account: CrownAccount) => {
  if (account.status === 'checking') return '正在检测 AdsPower 环境状态'
  if (account.note) return account.note
  if (account.status === 'success') return typeof account.balance === 'number' ? '账号在线，余额已获取' : '账号在线，余额未读取到'
  if (account.status === 'error') return '检测失败'
  return '等待检测'
}

const hasAdsPowerProfile = (account: Pick<CrownAccount, 'adsPowerProfileId'>) => Boolean(account.adsPowerProfileId?.trim())

const isBettingEnabledAccount = (account: CrownAccount) => account.bettingEnabled === true

const toExecutionAccounts = (accounts: CrownAccount[]) => accounts.map((account) => ({
  id: account.id,
  displayName: account.displayName,
  status: account.status,
  adsPowerProfileId: account.adsPowerProfileId,
  adsPowerStatus: account.adsPowerStatus,
  bettingEnabled: account.bettingEnabled === true,
}))

const adsPowerStatusLabel = (account: CrownAccount) => {
  if (!hasAdsPowerProfile(account)) return '未绑定 AdsPower 档案'
  if (account.adsPowerStatus === 'starting') return '正在打开 AdsPower 环境'
  if (account.adsPowerStatus === 'opened') return 'AdsPower 环境已打开'
  if (account.adsPowerStatus === 'closed') return 'AdsPower 环境未打开'
  if (account.adsPowerStatus === 'error') return account.adsPowerMessage || 'AdsPower 环境异常'
  return '已绑定 AdsPower 档案'
}

const adsPowerStatusColor = (account: CrownAccount) => {
  if (!hasAdsPowerProfile(account)) return 'default'
  if (account.adsPowerStatus === 'opened') return 'success'
  if (account.adsPowerStatus === 'starting') return 'processing'
  if (account.adsPowerStatus === 'closed') return 'warning'
  if (account.adsPowerStatus === 'error') return 'error'
  return 'blue'
}

const readStoredAccounts = (): CrownAccount[] => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
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
        loginUrl: item.loginUrl || CROWN_LOGIN_URL,
        adsPowerProfileId: item.adsPowerProfileId || '',
        adsPowerStatus: item.adsPowerStatus || (item.adsPowerProfileId ? 'unlinked' : undefined),
        adsPowerMessage: item.adsPowerMessage || undefined,
        adsPowerUpdatedAt: typeof item.adsPowerUpdatedAt === 'number' ? item.adsPowerUpdatedAt : undefined,
        bettingEnabled: typeof item.bettingEnabled === 'boolean' ? item.bettingEnabled : false,
        status: item.adsPowerProfileId && ['checking', 'error'].includes(item.status) ? 'unchecked' : (item.status || 'unchecked'),
        balance: typeof item.balance === 'number' ? item.balance : null,
      }))
    if (accounts.length !== storedAccounts.length) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(accounts))
    }
    return accounts
  } catch {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([]))
    return []
  }
}

const CrownBetting = () => {
  const [form] = Form.useForm<CrownAccountFormValues>()
  const [accounts, setAccounts] = useState<CrownAccount[]>(readStoredAccounts)
  const [modalOpen, setModalOpen] = useState(false)
  const [automationSettings, setAutomationSettings] = useState<AutomationSettings>(readStoredAutomationSettings)
  const [executionPlan, setExecutionPlan] = useState<AutoBettingExecutionPlan | null>(null)
  const [signalCandidates, setSignalCandidates] = useState<QueuedCrownAlertSignal[]>([])
  const [adsPowerStatus, setAdsPowerStatus] = useState<AdsPowerStatusResponse | null>(null)
  const [adsPowerChecking, setAdsPowerChecking] = useState(false)
  const {
    autoMode,
    autoEnabled,
    perAccountLimit,
    betLimit,
    minimumBetOdds,
    signalMaxAgeSeconds,
  } = automationSettings

  const saveAccounts = (nextAccounts: CrownAccount[]) => {
    setAccounts(nextAccounts)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(nextAccounts))
  }

  const syncAutoBettingEnabled = async (enabled: boolean) => {
    try {
      const response = await updateAutoBettingEnabled(enabled)
      if (response.data.code !== 0) {
        throw new Error(response.data.msg || '自动投注后端开关同步失败')
      }
    } catch (error: any) {
      if (enabled) {
        const disabledSettings = { ...readStoredAutomationSettings(), autoEnabled: false }
        persistAutomationSettings(disabledSettings)
        setAutomationSettings(disabledSettings)
      }
      message.error(extractApiErrorMessage(error, enabled ? '自动投注开启失败' : '自动投注关闭失败'))
    }
  }

  const updateAutomationSetting = <Key extends keyof AutomationSettings>(
    key: Key,
    value: AutomationSettings[Key],
  ) => {
    const nextSettings = {
      ...automationSettings,
      [key]: value,
    }
    setAutomationSettings(nextSettings)
    persistAutomationSettings(nextSettings)
    if (key === 'autoEnabled') {
      void syncAutoBettingEnabled(Boolean(value))
    }
  }

  useEffect(() => {
    let cancelled = false
    const syncAutoModeFromMonitorConfig = async () => {
      try {
        const response = await apiClient.post<ApiResponse<NotificationConfigResponse[]>>(
          '/system/notifications/configs/list',
          {},
        )
        if (cancelled || response.data.code !== 0 || !Array.isArray(response.data.data)) return
        const monitorMode = monitorModeFromNotificationConfigs(response.data.data)
        if (!monitorMode) return
        setAutomationSettings((currentSettings) => {
          if (currentSettings.autoMode === monitorMode) return currentSettings
          const nextSettings = { ...currentSettings, autoMode: monitorMode }
          localStorage.setItem(AUTOMATION_SETTINGS_STORAGE_KEY, JSON.stringify(nextSettings))
          return nextSettings
        })
      } catch {
        // Keep the local betting mode when monitor settings cannot be read.
      }
    }
    void syncAutoModeFromMonitorConfig()
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    void updateAutoBettingEnabled(readStoredAutomationSettings().autoEnabled).catch(() => undefined)
  }, [])

  const saveAutomationSettings = () => {
    persistAutomationSettings(automationSettings)
    message.success('投注设置已保存')
  }

  const totalBalance = useMemo(
    () => accounts.reduce((total, account) => total + (account.balance || 0), 0),
    [accounts],
  )
  const abnormalCount = accounts.filter((account) => account.status === 'error').length
  const boundProfileCount = accounts.filter(hasAdsPowerProfile).length
  const enabledBettingAccounts = useMemo(
    () => accounts.filter(isBettingEnabledAccount),
    [accounts],
  )
  const accountsRef = useRef<CrownAccount[]>(accounts)
  useEffect(() => {
    accountsRef.current = accounts
  }, [accounts])
  const updateAccount = useCallback((id: string, patch: Partial<CrownAccount>) => {
    setAccounts((currentAccounts) => {
      const nextAccounts = currentAccounts.map((account) => (
        account.id === id ? { ...account, ...patch } : account
      ))
      localStorage.setItem(STORAGE_KEY, JSON.stringify(nextAccounts))
      return nextAccounts
    })
  }, [])

  const matchAdsPowerCrownSession = useCallback(async (account: CrownAccount) => {
    const preferredProfileId = account.adsPowerProfileId?.trim()
    return apiClient.post<ApiResponse<AdsPowerCrownSessionResponse>>(
      '/auto-betting/adspower/crown-session/match',
      { loginName: account.loginName, loginUrl: account.loginUrl, preferredProfileId: preferredProfileId || undefined },
    )
  }, [])

  const checkAccount = async (account: CrownAccount) => {
    updateAccount(account.id, {
      status: 'checking',
      note: '正在检测 AdsPower 环境状态',
      lastCheckedAt: Date.now(),
    })
    try {
      const response = await matchAdsPowerCrownSession(account)
      if (response.data.code !== 0 || !response.data.data) {
        throw new Error(response.data.msg || '账号检测失败')
      }
      const result = response.data.data
      const checkedAt = result.checkedAt || Date.now()
      const isClosed = !result.opened && result.accountStatus === 'profile_closed'
      const nextAdsPowerStatus: AdsPowerProfileStatus = result.opened ? 'opened' : (isClosed ? 'closed' : 'error')
      const nextAccountStatus: CrownAccountStatus = result.loggedIn ? 'success' : (isClosed ? 'unchecked' : 'error')
      const note = result.loggedIn
        ? (typeof result.balance === 'number' ? '账号在线，余额已获取' : '账号在线，余额未读取到')
        : (isClosed ? 'AdsPower 环境未打开，请先打开环境并完成登录' : result.message || '账号检测失败')
      updateAccount(account.id, {
        status: nextAccountStatus,
        balance: typeof result.balance === 'number' ? result.balance : null,
        currency: result.currency || account.currency,
        adsPowerProfileId: result.loggedIn && result.profileId ? result.profileId : account.adsPowerProfileId,
        adsPowerStatus: nextAdsPowerStatus,
        adsPowerMessage: result.message,
        adsPowerUpdatedAt: checkedAt,
        lastCheckedAt: checkedAt,
        note,
      })
      if (result.loggedIn) {
        message.success('账号在线')
      } else if (isClosed) {
        message.warning('AdsPower 环境未打开')
      } else {
        message.error(result.message || '账号检测失败')
      }
    } catch (error: any) {
      const errorMessage = extractApiErrorMessage(error, '账号检测失败')
      updateAccount(account.id, {
        status: 'error',
        adsPowerStatus: 'error',
        balance: null,
        adsPowerMessage: errorMessage,
        adsPowerUpdatedAt: Date.now(),
        lastCheckedAt: Date.now(),
        note: errorMessage,
      })
      message.error(errorMessage)
    }
  }

  const checkAdsPowerStatus = async () => {
    setAdsPowerChecking(true)
    try {
      const response = await apiClient.post<ApiResponse<AdsPowerStatusResponse>>(
        '/auto-betting/adspower/status',
        {},
      )
      if (response.data.code !== 0 || !response.data.data) {
        throw new Error(response.data.msg || 'AdsPower 检测失败')
      }
      const result = response.data.data
      setAdsPowerStatus(result)
      if (result.available) {
        message.success('AdsPower 已连接')
      } else {
        message.warning(`AdsPower 未连接：${result.message}`)
      }
    } catch (error: any) {
      const checkedAt = Date.now()
      const errorMessage = extractApiErrorMessage(error, 'AdsPower 检测失败')
      setAdsPowerStatus({
        available: false,
        baseUrl: '',
        message: errorMessage,
        checkedAt,
      })
      message.error(errorMessage)
    } finally {
      setAdsPowerChecking(false)
    }
  }

  const openAdsPowerProfile = async (account: CrownAccount) => {
    const profileId = account.adsPowerProfileId?.trim()
    if (!profileId) {
      message.warning('未绑定 AdsPower 档案')
      return
    }
    updateAccount(account.id, {
      adsPowerStatus: 'starting',
      adsPowerMessage: '正在打开 AdsPower 环境',
      adsPowerUpdatedAt: Date.now(),
    })
    try {
      const response = await apiClient.post<ApiResponse<AdsPowerStartProfileResponse>>(
        '/auto-betting/adspower/start-profile',
        { profileId },
      )
      if (response.data.code !== 0 || !response.data.data) {
        throw new Error(response.data.msg || 'AdsPower 环境打开失败')
      }
      const result = response.data.data
      updateAccount(account.id, {
        adsPowerStatus: result.opened ? 'opened' : 'error',
        status: account.status === 'checking' ? 'unchecked' : account.status,
        adsPowerMessage: result.opened ? 'AdsPower 环境已打开，可人工登录或监控' : result.message,
        adsPowerUpdatedAt: result.openedAt || Date.now(),
        lastCheckedAt: result.openedAt || Date.now(),
      })
      if (result.opened) {
        message.success('AdsPower 环境已打开')
      } else {
        message.warning(`AdsPower 环境打开失败：${result.message}`)
      }
    } catch (error: any) {
      const errorMessage = extractApiErrorMessage(error, 'AdsPower 环境打开失败')
      updateAccount(account.id, {
        adsPowerStatus: 'error',
        adsPowerMessage: errorMessage,
        adsPowerUpdatedAt: Date.now(),
      })
      message.error(errorMessage)
    }
  }

  const addAccount = async () => {
    const values = await form.validateFields()
    const now = Date.now()
    const nextAccount: CrownAccount = {
      id: `crown-${now}`,
      displayName: values.displayName.trim(),
      loginName: values.loginName.trim(),
      loginUrl: values.loginUrl.trim(),
      adsPowerProfileId: values.adsPowerProfileId?.trim() || '',
      adsPowerStatus: values.adsPowerProfileId?.trim() ? 'unlinked' : undefined,
      bettingEnabled: false,
      status: 'unchecked',
      balance: null,
      currency: 'CNY',
      lastCheckedAt: now,
      note: values.note?.trim(),
    }
    saveAccounts([nextAccount, ...accounts])
    form.resetFields()
    setModalOpen(false)
    message.success('皇冠账号已添加')
    void checkAccount(nextAccount)
  }

  const deleteAccount = (id: string) => {
    saveAccounts(accounts.filter((account) => account.id !== id))
    message.success('皇冠账号已删除')
  }

  const refreshAccounts = async () => {
    for (const account of accounts) {
      await checkAccount(account)
      await new Promise((resolve) => setTimeout(resolve, 1200))
    }
    message.success('已完成账号状态检测')
  }

  const [executionRunning, setExecutionRunning] = useState(false)
  const [currentExecutionStep, setCurrentExecutionStep] = useState<CurrentExecutionStep>(null)
  const executionRunningRef = useRef(false)
  const automationPollingRef = useRef(false)
  const attemptedSignalAtRef = useRef<Map<string, number>>(new Map())
  const completedSignalKeysRef = useRef<Set<string>>(new Set())
  const mountedRef = useRef(true)

  useEffect(() => () => {
    mountedRef.current = false
  }, [])

  const executeLatestCrownSignal = useCallback(async () => {
    const currentAccounts = accountsRef.current
    if (automationPollingRef.current || executionRunningRef.current) {
      return
    }
    automationPollingRef.current = true
    const lockOwner = `page-${Date.now()}-${Math.random()}`
    if (!acquireCrownBettingAutomationLock(lockOwner)) {
      automationPollingRef.current = false
      return
    }
    let executionStarted = false
    try {
      const alertResponse = await apiClient.post<ApiResponse<OddsAlertRecord[]>>(
        '/odds-monitor/alerts/list',
        {},
      )
      if (!mountedRef.current) return
      if (alertResponse.data.code !== 0) {
        throw new Error(alertResponse.data.msg || '监控信号读取失败')
      }
      const now = Date.now()
      const candidates = filterFreshCrownAlertSignals(
        extractCrownAlertSignalCandidates(alertResponse.data.data, autoMode),
        now,
        signalMaxAgeSeconds,
      )
      const qualifiedCandidates = candidates.filter((candidate) => (
        candidate.targetOdds >= minimumBetOdds
      ))
      const signalSelectionOptions = {
        completedSignalKeys: completedSignalKeysRef.current,
        attemptedSignalAt: attemptedSignalAtRef.current,
        now,
        retryCooldownMs: AUTO_BETTING_SIGNAL_RETRY_COOLDOWN_MS,
      }
      const signalQueue = buildCrownAlertSignalQueue(qualifiedCandidates, signalSelectionOptions)
      setSignalCandidates(signalQueue)
      const signal = selectNextCrownAlertSignal(qualifiedCandidates, signalSelectionOptions)
      const selectedQueueItem = signal
        ? signalQueue.find((candidate) => autoBettingSignalKey(candidate) === autoBettingSignalKey(signal))
        : null
      const signalKey = signal ? autoBettingSignalKey(signal) : null
      if (signalKey && autoEnabled) {
        attemptedSignalAtRef.current.set(signalKey, now)
      }
      const shouldExecuteSignal = autoEnabled && Boolean(signal)
      if (shouldExecuteSignal) {
        executionStarted = true
        executionRunningRef.current = true
        setExecutionRunning(true)
        setCurrentExecutionStep({
          accountName: '-',
          stageLabel: '读取信号',
          phaseLabel: signal?.modeLabel || (autoMode === 'prematch' ? '赛前' : '滚球'),
          signalTitle: signal?.matchTitle || '等待监控信号',
          queueLabel: selectedQueueItem ? `#${selectedQueueItem.queuePosition}/${signalQueue.length}` : '-',
        })
      }
      const buildExecutionPlan = (planAccounts: CrownAccount[]) => buildAutoBettingExecutionPlan({
        signal,
        mode: autoMode,
        enabled: autoEnabled,
        perAccountLimit,
        betLimit,
        minimumBetOdds,
        accounts: toExecutionAccounts(planAccounts),
      })
      let nextPlan = buildExecutionPlan(currentAccounts)
      setExecutionPlan((currentPlan) => (
        signal || !currentPlan?.signal ? nextPlan : currentPlan
      ))
      if (!shouldExecuteSignal) {
        return
      }
      const executionAccounts = currentAccounts.filter(isBettingEnabledAccount)
      if (executionAccounts.length === 0) {
        setCurrentExecutionStep((current) => current ? { ...current, stageLabel: nextPlan.summary } : null)
        return
      }
      const activeSignal = signal
      if (!activeSignal) return
      {
      const executableRows = nextPlan.rows.filter((row) => row.status === 'passed' && row.stakeAmount > 0)
      if (executableRows.length === 0) {
        setCurrentExecutionStep((current) => current ? { ...current, stageLabel: nextPlan.summary } : null)
        if (signalKey) completedSignalKeysRef.current.add(signalKey)
        return
      }
      const accountById = new Map(currentAccounts.map((account) => [account.id, account]))
      const queueTimeout = Math.max(
        AUTO_BETTING_ACCOUNT_EXECUTION_TIMEOUT_MS,
        executableRows.length * AUTO_BETTING_ACCOUNT_EXECUTION_TIMEOUT_MS + 5000,
      )
      const queueFailureRows = (reason: string) => nextPlan.rows.map((row) => (
        executableRows.some((candidate) => candidate.id === row.id)
          ? {
              ...row,
              status: 'skipped' as const,
              statusLabel: '跳过',
              stakeAmount: 0,
              stopRetry: true,
              reason,
            }
          : row
      ))
      setCurrentExecutionStep({
        accountName: `${executableRows.length} 个账号`,
        stageLabel: '后端队列执行',
        phaseLabel: activeSignal.modeLabel,
        signalTitle: activeSignal.matchTitle,
        queueLabel: selectedQueueItem ? `#${selectedQueueItem.queuePosition}/${signalQueue.length}` : '-',
      })
      let queueDecisions: AutoBettingDecisionResponse[] = []
      try {
        const queueResponse = await apiClient.post<ApiResponse<AutoBettingDecisionResponse[]>>(
          '/auto-betting/signals/odds-monitor/execute-crown-queue',
          {
            signalSource: 'odds_monitor',
            bettingMode: activeSignal.bettingMode,
            matchPhase: activeSignal.matchPhase,
            leagueName: activeSignal.leagueName,
            matchTitle: activeSignal.matchTitle,
            marketType: activeSignal.marketType,
            lineValue: activeSignal.lineValue,
            selectionName: activeSignal.selectionName,
            referenceSourceKey: activeSignal.referenceSourceKey,
            targetSourceKey: activeSignal.targetSourceKey,
            referenceOdds: activeSignal.referenceOdds,
            targetOdds: activeSignal.targetOdds,
            minimumTargetOdds: minimumBetOdds,
            queuePosition: selectedQueueItem?.queuePosition,
            queueTotal: signalQueue.length,
            oddsChangeDirection: activeSignal.oddsChangeDirection,
            stakeAmount: executableRows[0].stakeAmount,
            accountStakeLimit: betLimit,
            capturedAt: activeSignal.sourceAlertCreatedAt || Date.now(),
            maxSignalAgeSeconds: signalMaxAgeSeconds,
            accounts: executableRows.map((row) => {
              const account = accountById.get(row.id)
              return {
                accountKey: row.id,
                accountDisplayName: row.accountName,
                profileId: account?.adsPowerProfileId?.trim() || '',
                loginUrl: account?.loginUrl || CROWN_LOGIN_URL,
                bettingEnabled: true,
              }
            }),
          },
          { timeout: queueTimeout },
        )
        if (!mountedRef.current) return
        if (queueResponse.data.code !== 0 || !Array.isArray(queueResponse.data.data)) {
          throw new Error(queueResponse.data.msg || '后端投注队列执行失败')
        }
        queueDecisions = queueResponse.data.data
      } catch (error: any) {
        const reason = extractApiErrorMessage(error, '后端投注队列执行失败')
        const finalRows = queueFailureRows(reason)
        setExecutionPlan({
          ...nextPlan,
          rows: finalRows,
          canExecute: false,
          totalStake: 0,
          availableAccountCount: 0,
          summary: '没有确认成功的下注',
        })
        if (signalKey) completedSignalKeysRef.current.add(signalKey)
        message.warning('本轮信号已记录失败，不再重复重试')
        setCurrentExecutionStep((current) => current ? { ...current, stageLabel: '本轮完成' } : null)
        return
      }

      const decisionByAccountKey = new Map(queueDecisions.map((decision) => [decision.accountKey, decision]))
      const finalRows = nextPlan.rows.map((row) => {
        const decision = decisionByAccountKey.get(row.id)
        if (!decision) return row
        const historyVerified = decision.status === 'placed' && decision.crownHistoryVerified === true
        const completedDuplicate = isCompletedDuplicateAutoBettingReason(decision.reason)
        return {
          ...row,
          status: historyVerified ? 'passed' as const : 'skipped' as const,
          statusLabel: historyVerified || completedDuplicate ? '已下注' : '跳过',
          stakeAmount: historyVerified ? row.stakeAmount : 0,
          stopRetry: true,
          retryable: false,
          reason: decision.crownBetReference
            ? `${formatAutoBettingReason(decision.reason)} ${decision.crownBetReference}`
            : formatAutoBettingReason(decision.reason),
        }
      })
      const placedRows = finalRows.filter((row) => row.status === 'passed')
      const placedStake = placedRows.reduce((total, row) => total + row.stakeAmount, 0)
      setExecutionPlan({
        ...nextPlan,
        rows: finalRows,
        canExecute: placedRows.length > 0,
        totalStake: placedStake,
        availableAccountCount: placedRows.length,
        summary: placedRows.length > 0
          ? `下注成功 ${placedRows.length} 个账号`
          : '没有确认成功的下注',
      })
      if (signalKey) completedSignalKeysRef.current.add(signalKey)
      if (placedRows.length > 0) {
        message.success('下注队列已完成，成功记录已写入后端')
      } else {
        message.warning('本轮信号已执行完毕，不再重复重试')
      }
      setCurrentExecutionStep((current) => current ? { ...current, stageLabel: '本轮完成' } : null)
      return
      }

    } catch (error: any) {
      message.error(extractApiErrorMessage(error, '监控信号读取失败'))
    } finally {
      releaseCrownBettingAutomationLock(lockOwner)
      automationPollingRef.current = false
      if (executionStarted) {
        executionRunningRef.current = false
        setExecutionRunning(false)
      }
    }
  }, [
    autoEnabled,
    autoMode,
    betLimit,
    minimumBetOdds,
    perAccountLimit,
    signalMaxAgeSeconds,
  ])

  useEffect(() => {
    void executeLatestCrownSignal()
    const intervalId = window.setInterval(() => {
      void executeLatestCrownSignal()
    }, AUTO_BETTING_POLL_INTERVAL_MS)
    return () => window.clearInterval(intervalId)
  }, [autoEnabled, enabledBettingAccounts.length, executeLatestCrownSignal])

  const currentExecutionSignal = executionPlan?.signal
  const currentExecutionSignalQueueItem = currentExecutionSignal
    ? signalCandidates.find((candidate) => autoBettingSignalKey(candidate) === autoBettingSignalKey(currentExecutionSignal))
    : null
  const currentExecutionSignalLabel = executionRunning
    ? '正在投注盘口'
    : currentExecutionSignal ? '最近处理盘口' : '等待合格信号'
  const hasExecutionAttemptResult = executionPlan
    ? executionPlan.summary.startsWith('下注成功') || executionPlan.summary === '没有确认成功的下注'
    : false
  const executionAccountTagLabel = executionPlan
    ? `${hasExecutionAttemptResult ? '已下注账号' : '可投账号'} ${executionPlan.availableAccountCount} 个`
    : ''
  const executionAccountTagColor = hasExecutionAttemptResult
    ? executionPlan && executionPlan.availableAccountCount > 0 ? 'success' : 'default'
    : 'blue'

  return (
    <div>
      <Space align="center" style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
        <div>
          <Title level={2} style={{ margin: 0 }}>皇冠投注</Title>
          <Text type="secondary">监控保持本地浏览器直连，投注检测和执行只走 AdsPower 环境。</Text>
        </div>
        <Space wrap>
          <Button icon={<ApiOutlined />} loading={adsPowerChecking} onClick={checkAdsPowerStatus}>
            检测 AdsPower
          </Button>
          <Button icon={<ReloadOutlined />} onClick={refreshAccounts} disabled={accounts.length === 0}>
            刷新状态
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
            添加皇冠账号
          </Button>
        </Space>
      </Space>

      <div style={summaryGridStyle}>
        <Card size="small">
          <Statistic title="账号数量" value={accounts.length} suffix="个" />
        </Card>
        <Card size="small">
          <Statistic title="账号余额" value={totalBalance} precision={2} prefix="¥" />
        </Card>
        <Card size="small">
          <Statistic title="异常账号" value={abnormalCount} suffix="个" valueStyle={{ color: abnormalCount ? '#cf1322' : '#3f8600' }} />
        </Card>
        <Card size="small">
          <Statistic title="AdsPower 环境" value={boundProfileCount} suffix="个" />
          {adsPowerStatus ? (
            <Tag color={adsPowerStatus.available ? 'success' : 'error'} style={{ marginTop: 6 }}>
              {adsPowerStatus.available ? 'AdsPower 已连接' : 'AdsPower 未连接'}
            </Tag>
          ) : null}
        </Card>
      </div>

      <Card
        size="small"
        title="账号与 AdsPower 环境"
        extra={(
          <Space wrap>
            <Tag color="blue">每个账号绑定一个独立 AdsPower 环境</Tag>
            <Tag color="default">监控账号不进入投注区</Tag>
          </Space>
        )}
        styles={compactAccountCardStyles}
      >
        {accounts.length === 0 ? (
          <Empty description="暂无皇冠账号" />
        ) : (
          <div>
            <div style={{ ...accountGridStyle, padding: '0 0 8px', borderBottom: '1px solid #f0f0f0' }}>
              <Text strong>账号信息</Text>
              <Text strong>账号状态</Text>
              <Text strong>账户余额</Text>
              <Text strong>检测结果</Text>
              <Text strong>AdsPower 环境</Text>
              <Text strong>操作</Text>
            </div>
            {accounts.map((account) => (
              <div key={account.id} style={{ ...accountGridStyle, ...accountRowStyle }}>
                <div style={valueBlockStyle}>
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    <Space size={8} wrap>
                      <Text strong>{account.displayName}</Text>
                      <Tag color={account.bettingEnabled === true ? 'success' : 'default'}>
                        {account.bettingEnabled === true ? '投注启用' : '投注停用'}
                      </Tag>
                    </Space>
                    <Text type="secondary">登录账号：{account.loginName}</Text>
                    <a
                      href={account.loginUrl}
                      target="_blank"
                      rel="noreferrer"
                      style={{
                        display: 'inline-block',
                        maxWidth: '100%',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {account.loginUrl}
                    </a>
                  </Space>
                </div>

                <div>
                  <Space direction="vertical" size={4}>
                    <Tag color={statusMeta[account.status].color}>{statusMeta[account.status].label}</Tag>
                    <Text type="secondary">更新：{formatDateTime(account.lastCheckedAt)}</Text>
                  </Space>
                </div>

                <div>
                  <Text strong={typeof account.balance === 'number'} type={typeof account.balance === 'number' ? undefined : 'secondary'}>
                    {formatBalanceState(account)}
                  </Text>
                </div>

                <div style={valueBlockStyle}>
                  <Space direction="vertical" size={6}>
                    <Text type={account.status === 'error' ? 'danger' : 'secondary'}>
                      {accountCheckSummary(account)}
                    </Text>
                    <Tag icon={<ApiOutlined />}>投注自动化使用 AdsPower 环境</Tag>
                  </Space>
                </div>

                <div style={valueBlockStyle}>
                  <Space direction="vertical" size={5} style={{ width: '100%' }}>
                    <Tag color={adsPowerStatusColor(account)}>{adsPowerStatusLabel(account)}</Tag>
                    {hasAdsPowerProfile(account) ? (
                      <Text
                        type="secondary"
                        style={{
                          display: 'inline-block',
                          maxWidth: '100%',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        AdsPower 档案 ID / 编号：{account.adsPowerProfileId}
                      </Text>
                    ) : null}
                    {account.adsPowerMessage && account.adsPowerStatus === 'error' ? (
                      <Text type="danger">{account.adsPowerMessage}</Text>
                    ) : null}
                  </Space>
                </div>

                <Space wrap size={[6, 4]}>
                  <Switch
                    size="small"
                    checked={account.bettingEnabled === true}
                    checkedChildren="启用"
                    unCheckedChildren="停用"
                    disabled={!hasAdsPowerProfile(account)}
                    onChange={(checked) => updateAccount(account.id, { bettingEnabled: checked })}
                  />
                  <Button
                    size="small"
                    icon={<ApiOutlined />}
                    loading={account.adsPowerStatus === 'starting'}
                    disabled={!hasAdsPowerProfile(account)}
                    onClick={() => openAdsPowerProfile(account)}
                  >
                    打开环境
                  </Button>
                  <Button
                    size="small"
                    loading={account.status === 'checking'}
                    onClick={() => checkAccount(account)}
                  >
                    检测一次
                  </Button>
                  <Popconfirm
                    title="删除账号"
                    description="确认删除这个皇冠账号？"
                    okText="删除"
                    cancelText="取消"
                    onConfirm={() => deleteAccount(account.id)}
                  >
                    <Button size="small" danger>删除账号</Button>
                  </Popconfirm>
                </Space>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card
        title="自动化接入投注功能"
        size="small"
        style={{ marginTop: 16 }}
        extra={<Tag color={autoEnabled ? 'green' : 'default'}>{autoEnabled ? '自动投注' : '自动投注关闭'}</Tag>}
      >
        <div style={automationControlGridStyle}>
          <div style={automationFieldStyle}>
            <Text strong>投注模式</Text>
            <div style={{ marginTop: 8 }}>
              <Segmented
                options={automationModeOptions}
                value={autoMode}
                onChange={(value) => updateAutomationSetting('autoMode', value as AutoBettingMode)}
              />
            </div>
          </div>
          <div style={automationFieldStyle}>
            <Text strong>自动投注开关</Text>
            <div style={{ marginTop: 8 }}>
              <Switch
                checked={autoEnabled}
                checkedChildren="开启"
                unCheckedChildren="关闭"
                onChange={(checked) => updateAutomationSetting('autoEnabled', checked)}
              />
            </div>
          </div>
          <div style={automationFieldStyle}>
            <Text strong>每号金额限制</Text>
            <InputNumber
              min={50}
              max={500}
              step={10}
              precision={0}
              value={perAccountLimit}
              onChange={(value) => updateAutomationSetting('perAccountLimit', Number(value || 0))}
              style={{ width: '100%', marginTop: 8 }}
            />
          </div>
          <div style={automationFieldStyle}>
            <Text strong>投注上限</Text>
            <InputNumber
              min={10}
              step={10}
              precision={0}
              value={betLimit}
              onChange={(value) => updateAutomationSetting('betLimit', Number(value || 0))}
              style={{ width: '100%', marginTop: 8 }}
            />
          </div>
          <div style={automationFieldStyle}>
            <Text strong>最低投注水位</Text>
            <InputNumber
              min={0.01}
              step={0.01}
              precision={2}
              value={minimumBetOdds}
              onChange={(value) => updateAutomationSetting('minimumBetOdds', Number(value || 0))}
              style={{ width: '100%', marginTop: 8 }}
            />
          </div>
          <div style={automationFieldStyle}>
            <Text strong>信号有效期(秒)</Text>
            <InputNumber
              min={1}
              max={3600}
              step={30}
              precision={0}
              value={signalMaxAgeSeconds}
              onChange={(value) => updateAutomationSetting('signalMaxAgeSeconds', Number(value || 0))}
              style={{ width: '100%', marginTop: 8 }}
            />
          </div>
          <div style={automationFieldStyle}>
            <Text strong>设置</Text>
            <Button
              icon={<SaveOutlined />}
              onClick={saveAutomationSettings}
              style={{ width: '100%', marginTop: 8 }}
            >
              保存设置
            </Button>
          </div>
        </div>

        <div style={{ marginBottom: 12 }}>
          <Space wrap>
            <Tag color={autoEnabled ? 'processing' : 'default'}>
              {autoEnabled ? '自动监听中' : '自动投注已关闭'}
            </Tag>
            {executionRunning ? <Tag color="blue">正在投注盘口</Tag> : null}
            <Tag color="blue">信号来源：采集系统合格回传</Tag>
            <Tag color={enabledBettingAccounts.length > 0 ? 'success' : 'error'}>
              启用账号 {enabledBettingAccounts.length} 个
            </Tag>
            <Tag color="default">异常账号自动跳过</Tag>
          </Space>
        </div>

        <div style={automationSignalStyle}>
          <div>
            <Text type="secondary">{currentExecutionSignalLabel}</Text>
            <div>
              <Space size={6} wrap>
                <Text strong>{currentExecutionSignal?.matchTitle || '等待监控信号'}</Text>
                {currentExecutionSignalQueueItem ? (
                  <Tag color={executionRunning ? 'processing' : 'default'}>队列 #{currentExecutionSignalQueueItem.queuePosition}</Tag>
                ) : null}
              </Space>
            </div>
          </div>
          <div>
            <Text type="secondary">模式</Text>
            <div>
              <Tag color={(currentExecutionSignal?.matchPhase || autoMode) === 'prematch' ? 'blue' : 'orange'}>
                {currentExecutionSignal?.modeLabel || (autoMode === 'prematch' ? '赛前' : '滚球')}
              </Tag>
            </div>
          </div>
          <div>
            <Text type="secondary">盘口</Text>
            <div>{currentExecutionSignal?.marketTitle || '-'}</div>
          </div>
          <div>
            <Text type="secondary">投注选项</Text>
            <div>{currentExecutionSignal?.selectionName || '-'}</div>
          </div>
          <div>
            <Text type="secondary">目标赔率</Text>
            <div>{currentExecutionSignal ? currentExecutionSignal.odds.toFixed(3) : '-'}</div>
          </div>
          <div>
            <Text type="secondary">投注逻辑</Text>
            <div>{currentExecutionSignal?.bettingLogic || '-'}</div>
          </div>
        </div>

        {currentExecutionStep ? (
          <div style={executionStageStyle}>
            <div>
              <Text type="secondary">当前阶段</Text>
              <div><Tag color={executionRunning ? 'processing' : 'default'}>{currentExecutionStep.stageLabel}</Tag></div>
            </div>
            <div>
              <Text type="secondary">当前账号</Text>
              <div><Text strong>{currentExecutionStep.accountName}</Text></div>
            </div>
            <div>
              <Text type="secondary">当前模式</Text>
              <div>{currentExecutionStep.phaseLabel}</div>
            </div>
            <div>
              <Text type="secondary">当前队列</Text>
              <div>{currentExecutionStep.queueLabel}</div>
            </div>
            <div>
              <Text type="secondary">当前信号</Text>
              <div>{currentExecutionStep.signalTitle}</div>
            </div>
          </div>
        ) : null}

        <div style={{ marginBottom: 12 }}>
          <Space wrap style={{ marginBottom: 8 }}>
            <Text strong>候选信号盘口</Text>
            <Tag color="blue">采集系统合格回传</Tag>
            <Tag color="processing">按投注顺序排队</Tag>
          </Space>
          {signalCandidates.length > 0 ? (
            <div>
              <div style={{ ...signalCandidateGridStyle, paddingBottom: 8, borderBottom: '1px solid #f0f0f0' }}>
                <Text strong>顺序</Text>
                <Text strong>比赛</Text>
                <Text strong>模式</Text>
                <Text strong>盘口</Text>
                <Text strong>投注选项</Text>
                <Text strong>水位变化</Text>
                <Text strong>投注逻辑</Text>
              </div>
              {signalCandidates.map((candidate) => (
                <div
                  key={`${candidate.sourceAlertId || candidate.matchTitle}-${candidate.marketTitle}-${candidate.selectionName}-${candidate.targetOdds}`}
                  style={{ ...signalCandidateGridStyle, ...signalCandidateRowStyle }}
                >
                  <Tag color={candidate.queueStatus === 'ready' ? 'processing' : 'default'}>#{candidate.queuePosition}</Tag>
                  <Text>{candidate.matchTitle}</Text>
                  <Tag color={candidate.matchPhase === 'prematch' ? 'blue' : 'orange'}>{candidate.modeLabel}</Tag>
                  <Space direction="vertical" size={0}>
                    <Text>{candidate.marketTitle}</Text>
                  </Space>
                  <Text>{candidate.selectionName}</Text>
                  <Text>{candidate.referenceOdds.toFixed(3)} → {candidate.targetOdds.toFixed(3)}</Text>
                  <Text type="secondary">{candidate.bettingLogic}</Text>
                </div>
              ))}
            </div>
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合配置的候选盘口" />
          )}
        </div>

        <div style={{ marginBottom: 8 }}>
          <Space wrap>
            <Text strong>自动投注结果</Text>
            {executionPlan ? (
              <>
                <Tag color="processing">{executionPlan.modeLabel}</Tag>
                <Tag color="blue">总金额 {formatCurrency(executionPlan.totalStake)}</Tag>
                <Tag color={executionAccountTagColor}>{executionAccountTagLabel}</Tag>
                <Text type="secondary">{executionPlan.summary}</Text>
              </>
            ) : null}
          </Space>
        </div>

        {executionPlan ? (
          <div>
            <div style={{ ...executionResultGridStyle, paddingBottom: 8, borderBottom: '1px solid #f0f0f0' }}>
              <Text strong>账号</Text>
              <Text strong>结果</Text>
              <Text strong>模式</Text>
              <Text strong>比赛</Text>
              <Text strong>盘口</Text>
              <Text strong>赔率</Text>
              <Text strong>金额</Text>
              <Text strong>原因</Text>
            </div>
            {executionPlan.rows.length > 0 ? executionPlan.rows.map((row) => (
              <div key={row.id} style={{ ...executionResultGridStyle, ...executionResultRowStyle }}>
                <Text strong={row.status === 'passed'}>{row.accountName}</Text>
                <Tag color={row.status === 'passed' ? 'success' : 'default'}>{row.statusLabel}</Tag>
                <Tag color={row.matchPhase === 'prematch' ? 'blue' : 'orange'}>{row.modeLabel}</Tag>
                <Text>{row.matchTitle}</Text>
                <Space direction="vertical" size={0}>
                  <Text>{row.marketTitle}</Text>
                  <Text type="secondary">{row.selectionName}</Text>
                  <Text type="secondary">{row.bettingLogic}</Text>
                </Space>
                <Text>{row.odds.toFixed(3)}</Text>
                <Text strong={row.stakeAmount > 0}>{formatCurrency(row.stakeAmount)}</Text>
                <Text type={row.status === 'passed' ? 'secondary' : 'warning'}>{row.reason}</Text>
              </div>
            )) : (
              <Empty description={executionPlan.summary} />
            )}
          </div>
        ) : (
          <Empty description="等待自动投注信号" />
        )}
      </Card>

      <Modal
        title="添加皇冠账号"
        open={modalOpen}
        okText="添加"
        cancelText="取消"
        onOk={addAccount}
        onCancel={() => setModalOpen(false)}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          initialValues={{ loginUrl: CROWN_LOGIN_URL }}
        >
          <Form.Item
            label="账号名称"
            name="displayName"
            rules={[{ required: true, message: '请输入账号名称' }]}
          >
            <Input placeholder="例如：皇冠主账号" />
          </Form.Item>
          <Form.Item
            label="登录账号"
            name="loginName"
            rules={[{ required: true, message: '请输入登录账号' }]}
          >
            <Input placeholder="皇冠登录账号" />
          </Form.Item>
          <Form.Item
            label="AdsPower 档案 ID / 编号"
            name="adsPowerProfileId"
          >
            <Input placeholder="可不填；也可填 AdsPower 环境ID或左侧编号，程序会自动匹配已登录环境" />
          </Form.Item>
          <Form.Item
            label="登录网站"
            name="loginUrl"
            rules={[
              { required: true, message: '请输入登录网站' },
              { type: 'url', message: '请输入完整网站地址，例如 https://m407.mos077.com/' },
            ]}
          >
            <Input placeholder="https://m407.mos077.com/" />
          </Form.Item>
          <Form.Item label="备注" name="note">
            <Input.TextArea rows={3} placeholder="可填写用途、归属或异常说明" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default CrownBetting
