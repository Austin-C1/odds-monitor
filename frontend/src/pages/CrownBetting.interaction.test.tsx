// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { apiClient } from '../services/api'
import CrownBetting from './CrownBetting'

const dashboardFor = (status: 'scheduled' | 'live') => ({
  matches: [],
  selectedMatch: {
    match: status === 'live'
      ? {
          id: 2,
          leagueName: '意甲',
          homeTeam: '罗马',
          awayTeam: '拉齐奥',
          startTime: Date.now() - 60_000,
          status: 'live',
          sourceCount: 2,
          alertCount: 0,
          matchedPlatforms: ['pinnacle', 'crown'],
        }
      : {
          id: 1,
          leagueName: '英超',
          homeTeam: '曼城',
          awayTeam: '利物浦',
          startTime: Date.now() + 60_000,
          status: 'scheduled',
          sourceCount: 2,
          alertCount: 0,
          matchedPlatforms: ['pinnacle', 'crown'],
        },
    metrics: status === 'live'
      ? [
          { label: 'total over 2.5', value: '1.94', trend: 'up', sourceKey: 'pinnacle' },
          { label: 'total over 2.5', value: '0.98', trend: 'up', sourceKey: 'crown' },
        ]
      : [
          { label: 'handicap home -0.5', value: '1.92', trend: 'stable', sourceKey: 'pinnacle' },
          { label: 'handicap home -0.5', value: '0.95', trend: 'stable', sourceKey: 'crown' },
        ],
    oddsHistory: [],
    platformMatches: [],
  },
})
const mockApiState = vi.hoisted(() => ({
  dashboard: null as any,
  alerts: [] as any[],
  crownSession: null as any,
  executionResults: [] as any[],
  activeExecutions: 0,
  maxConcurrentExecutions: 0,
  notificationConfigs: [] as any[],
  intentId: 9000,
}))

vi.mock('../services/api', () => ({
  apiClient: {
    post: vi.fn((url: string, data?: any) => {
      if (url === '/odds-monitor/dashboard') {
        return Promise.resolve({ data: { code: 0, data: mockApiState.dashboard } })
      }
      if (url === '/odds-monitor/alerts/list') {
        return Promise.resolve({ data: { code: 0, data: mockApiState.alerts } })
      }
      if (url === '/system/notifications/configs/list') {
        return Promise.resolve({ data: { code: 0, data: mockApiState.notificationConfigs } })
      }
      if (url === '/system/config/get') {
        return Promise.resolve({ data: { code: 0, data: { autoBettingEnabled: true } } })
      }
      if (url === '/system/config/auto-betting-enabled/update') {
        return Promise.resolve({ data: { code: 0, data: { autoBettingEnabled: data?.autoBettingEnabled === true } } })
      }
      if (url === '/auto-betting/adspower/status') {
        return Promise.resolve({
          data: {
            code: 0,
            data: { available: true, baseUrl: 'http://localhost:50325', message: 'success', checkedAt: Date.now() },
          },
        })
      }
      if (url === '/auto-betting/adspower/start-profile') {
        return Promise.resolve({
          data: {
            code: 0,
            data: { profileId: data?.profileId || 'profile-a', opened: true, message: 'success', openedAt: Date.now() },
          },
        })
      }
      if (url === '/auto-betting/adspower/crown-session') {
        return Promise.resolve({
          data: {
            code: 0,
            data: mockApiState.crownSession || {
              profileId: data?.profileId || 'profile-a',
              opened: true,
              loggedIn: true,
              accountStatus: 'online',
              balance: 2000,
              currency: 'CNY',
              message: '账号在线，余额已获取',
              debugPort: '39555',
              checkedAt: Date.now(),
            },
          },
        })
      }
      if (url === '/auto-betting/adspower/crown-session/match') {
        return Promise.resolve({
          data: {
            code: 0,
            data: mockApiState.crownSession || {
              profileId: 'matched-profile',
              opened: true,
              loggedIn: true,
              accountStatus: 'online',
              balance: 2000,
              currency: 'CNY',
              message: '账号在线，余额已获取',
              debugPort: '39555',
              checkedAt: Date.now(),
            },
          },
        })
      }
      if (url === '/auto-betting/signals/odds-monitor') {
        mockApiState.intentId += 1
        return Promise.resolve({
          data: {
            code: 0,
            data: {
              id: mockApiState.intentId,
              status: 'ready',
              reason: 'accepted',
              dedupeKey: 'demo',
              signalSource: 'odds_monitor',
              bettingMode: data?.bettingMode,
              matchPhase: data?.matchPhase,
              accountKey: data?.accountKey,
              leagueName: data?.leagueName,
              matchTitle: data?.matchTitle,
              marketType: data?.marketType,
              lineValue: data?.lineValue,
              selectionName: data?.selectionName,
              referenceSourceKey: data?.referenceSourceKey || 'pinnacle',
              targetSourceKey: data?.targetSourceKey || 'crown',
              referenceOdds: data?.referenceOdds,
              targetOdds: data?.targetOdds,
              targetDecimalOdds: Number(data?.targetOdds || 0) + 1,
              decimalEdge: 0.03,
              stakeAmount: data?.stakeAmount,
              capturedAt: Date.now(),
              createdAt: Date.now(),
            },
          },
        })
      }
      if (/^\/auto-betting\/intents\/\d+\/execute-crown$/.test(url)) {
        mockApiState.activeExecutions = Math.max(0, mockApiState.activeExecutions) + 1
        mockApiState.maxConcurrentExecutions = Math.max(
          mockApiState.maxConcurrentExecutions,
          mockApiState.activeExecutions,
        )
        const executionResult = mockApiState.executionResults.length > 0
          ? mockApiState.executionResults.shift()
          : {
              status: 'placed',
              reason: 'crown_history_verified',
              crownHistoryVerified: true,
              crownBetReference: 'CROWN-10001',
            }
        const response = {
          data: {
            code: 0,
            data: {
              id: Number(url.split('/')[3]),
              status: executionResult.status,
              reason: executionResult.reason,
              crownHistoryVerified: executionResult.crownHistoryVerified,
              crownHistoryCheckedAt: Date.now(),
              crownBetReference: executionResult.crownBetReference,
            },
          },
        }
        const finish = () => {
          mockApiState.activeExecutions = Math.max(0, mockApiState.activeExecutions - 1)
          return response
        }
        if (executionResult.delayMs) {
          return new Promise((resolve) => setTimeout(() => resolve(finish()), executionResult.delayMs))
        }
        return Promise.resolve(finish())
      }
      return Promise.resolve({ data: { code: 0, data: [] } })
    }),
  },
}))

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const writeAccounts = (accounts?: any[]) => {
  window.localStorage.setItem('crown-betting-accounts', JSON.stringify(accounts || [
    {
      id: 'account-a',
      displayName: '投注主账号',
      loginName: 'demo_a',
      loginUrl: 'https://m407.mos077.com/',
      adsPowerProfileId: 'profile-a',
      adsPowerStatus: 'opened',
      bettingEnabled: true,
      status: 'success',
      balance: 1200,
      currency: 'CNY',
      lastCheckedAt: Date.now(),
    },
    {
      id: 'account-b',
      displayName: '投注副账号',
      loginName: 'demo_b',
      loginUrl: 'https://m407.mos077.com/',
      adsPowerProfileId: 'profile-b',
      adsPowerStatus: 'opened',
      bettingEnabled: true,
      status: 'success',
      balance: 900,
      currency: 'CNY',
      lastCheckedAt: Date.now(),
    },
    {
      id: 'account-c',
      displayName: '投注异常账号',
      loginName: 'demo_c',
      loginUrl: 'https://m407.mos077.com/',
      adsPowerProfileId: '',
      bettingEnabled: false,
      status: 'error',
      balance: null,
      currency: 'CNY',
      lastCheckedAt: Date.now(),
    },
  ]))
}

describe('CrownBetting auto betting execution interaction', () => {
  afterEach(() => {
    cleanup()
  })

  beforeEach(() => {
    vi.clearAllMocks()
    mockApiState.dashboard = dashboardFor('scheduled')
    mockApiState.intentId = 9000
    mockApiState.alerts = [
      {
        id: 1001,
        alertType: 'odds_change',
        severity: 'info',
        matchName: '794',
        title: '赔率变动：米亚尔比 vs 哈马比',
        message: `滚球赔率变动

联赛：瑞典杯
比赛：米亚尔比 vs 哈马比
进行：第 2 分钟
比分：2-1

盘口：让球 主队 0/0.5
平博：0.71 -> 0.75
皇冠：0.73 -> 0.76

盘口：让球 客队 0/0.5
平博：1.17 -> 1.13
皇冠：1.15 -> 1.12

筛选：动水通过 / 合水通过
时间：2026-05-14 16:04:10`,
        createdAt: Date.now(),
        acknowledged: false,
      },
    ]
    mockApiState.crownSession = null
    mockApiState.executionResults = []
    mockApiState.activeExecutions = 0
    mockApiState.maxConcurrentExecutions = 0
    mockApiState.notificationConfigs = []
    const storage = new Map<string, string>()
    Object.defineProperty(window, 'localStorage', {
      writable: true,
      configurable: true,
      value: {
        getItem: (key: string) => storage.get(key) ?? null,
        setItem: (key: string, value: string) => storage.set(key, value),
        removeItem: (key: string) => storage.delete(key),
        clear: () => storage.clear(),
      },
    })
    Object.defineProperty(window, 'ResizeObserver', {
      writable: true,
      configurable: true,
      value: ResizeObserverMock,
    })
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      configurable: true,
      value: vi.fn().mockImplementation((query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      })),
    })
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'prematch',
      autoEnabled: true,
      perAccountLimit: 50,
      betLimit: 100,
      minimumBetOdds: 0.70,
    }))
    writeAccounts()
  })

  it('runs prematch and live checks from crown alert signals only', async () => {
    mockApiState.alerts = [
      {
        id: 1000,
        alertType: 'odds_change',
        severity: 'info',
        matchName: '1000',
        title: '赔率变动：曼城 vs 利物浦',
        message: `赛前赔率变动

联赛：英超
比赛：曼城 vs 利物浦

盘口：让球 主队 -0.5
平博：0.91 -> 0.95
皇冠：0.90 -> 0.94

盘口：让球 客队 -0.5
平博：1.11 -> 1.07
皇冠：1.10 -> 1.06

筛选：动水通过 / 合水通过
时间：2026-05-14 16:04:10`,
        createdAt: Date.now(),
        acknowledged: false,
      },
    ]
    render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    expect(screen.queryByText(/阿森纳|切尔西|国际米兰|尤文图斯/)).toBeNull()
    expect(screen.queryByRole('button', { name: /运行实际测试/ })).toBeNull()

    await waitFor(() => {
      expect(screen.getAllByText('曼城 vs 利物浦').length).toBeGreaterThan(0)
      expect(screen.getAllByText('已下注').length).toBe(2)
      expect(screen.queryByText('跳过')).toBeNull()
    })
    expect(screen.getByText('候选信号盘口')).toBeTruthy()
    expect(screen.getByText('采集系统合格回传')).toBeTruthy()
    expect(screen.getByText('按投注顺序排队')).toBeTruthy()
    let signalCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
    expect(signalCalls).toHaveLength(2)
    expect(signalCalls.every(([, body]) => (
      body.bettingMode === 'prematch' &&
      body.matchPhase === 'prematch' &&
      body.matchTitle === '曼城 vs 利物浦' &&
      body.selectionName === '曼城' &&
      body.referenceSourceKey === 'crown' &&
      body.queuePosition === 1 &&
      body.queueTotal === 2 &&
      body.maxSignalAgeSeconds === 360
    ))).toBe(true)
    let executeCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
    expect(executeCalls).toHaveLength(2)
    expect(executeCalls.every(([, body]) => (
      body.profileId &&
      body.loginUrl === 'https://m407.mos077.com/' &&
      body.minimumTargetOdds === 0.94
    ))).toBe(true)
    expect(executeCalls.every(([, , config]) => config?.timeout === 30000)).toBe(true)

    expect(screen.getByText('信号来源：采集系统合格回传')).toBeTruthy()
    expect(screen.getByText('按投注顺序排队')).toBeTruthy()
    expect(screen.getByText('#1')).toBeTruthy()
    expect(screen.getByText('#2')).toBeTruthy()

    mockApiState.alerts = [
      {
        id: 1001,
        alertType: 'odds_change',
        severity: 'info',
        matchName: '1001',
        title: '赔率变动：罗马 vs 拉齐奥',
        message: `滚球赔率变动

联赛：意甲
比赛：罗马 vs 拉齐奥
进行：第 2 分钟
比分：0-0

盘口：大小球 大球 2.5
平博：0.95 -> 0.99
皇冠：0.94 -> 0.98

盘口：大小球 小球 2.5
平博：1.08 -> 1.03
皇冠：1.06 -> 1.02

筛选：动水通过 / 合水通过
时间：2026-05-14 16:04:20`,
        createdAt: Date.now(),
        acknowledged: false,
      },
    ]
    fireEvent.click(screen.getByText('滚球'))

    await waitFor(() => {
      expect(screen.getAllByText('罗马 vs 拉齐奥').length).toBeGreaterThan(0)
      expect(screen.getAllByText('大球').length).toBeGreaterThan(0)
      const latestSignalCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
        .slice(-2)
      expect(latestSignalCalls).toHaveLength(2)
      expect(latestSignalCalls.every(([, body]) => (
        body.bettingMode === 'live' &&
        body.matchPhase === 'live' &&
        body.matchTitle === '罗马 vs 拉齐奥' &&
        body.selectionName === '大球' &&
        body.referenceSourceKey === 'crown'
      ))).toBe(true)
    })
    signalCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
      .slice(-2)
    expect(signalCalls).toHaveLength(2)
    expect(signalCalls.every(([, body]) => (
      body.bettingMode === 'live' &&
      body.matchPhase === 'live' &&
      body.matchTitle === '罗马 vs 拉齐奥' &&
      body.selectionName === '大球' &&
      body.referenceSourceKey === 'crown'
    ))).toBe(true)
    executeCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
    expect(executeCalls).toHaveLength(4)
    expect(screen.queryByText(/阿森纳|切尔西|国际米兰|尤文图斯/)).toBeNull()
  }, 30000)

  it('checks and executes one account before preparing the next account', async () => {
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'live',
      autoEnabled: true,
      perAccountLimit: 50,
      betLimit: 100,
      minimumBetOdds: 0.70,
      signalMaxAgeSeconds: 360,
    }))

    render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    await waitFor(() => {
      const executeCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
      expect(executeCalls).toHaveLength(2)
    })

    const calls = vi.mocked(apiClient.post).mock.calls
    const checkIndexes = calls
      .map(([url], index) => url === '/auto-betting/adspower/crown-session/match' ? index : -1)
      .filter((index) => index >= 0)
    const executeIndexes = calls
      .map(([url], index) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)) ? index : -1)
      .filter((index) => index >= 0)

    expect(checkIndexes).toHaveLength(2)
    expect(executeIndexes).toHaveLength(2)
    expect(checkIndexes[0]).toBeLessThan(executeIndexes[0])
    expect(executeIndexes[0]).toBeLessThan(checkIndexes[1])
    expect(checkIndexes[1]).toBeLessThan(executeIndexes[1])
  }, 30000)

  it('shows the active betting market while automatic betting is running', async () => {
    mockApiState.alerts = [
      {
        id: 1101,
        alertType: 'odds_change',
        severity: 'info',
        matchName: '1101',
        title: '赔率变动：罗马 vs 拉齐奥',
        message: `滚球赔率变动

联赛：意甲
比赛：罗马 vs 拉齐奥
进行：第 2 分钟
比分：0-0

盘口：大小球 大球 2.5
皇冠：0.94 -> 0.98

盘口：大小球 小球 2.5
皇冠：1.06 -> 1.02

筛选：动水通过 / 合水通过
时间：2026-05-14 16:04:20`,
        createdAt: Date.now(),
        acknowledged: false,
      },
    ]
    mockApiState.executionResults = [
      {
        status: 'placed',
        reason: 'crown_history_verified',
        crownHistoryVerified: true,
        crownBetReference: 'CROWN-1101',
        delayMs: 500,
      },
    ]
    writeAccounts([
      {
        id: 'account-a',
        displayName: 'Enabled account',
        loginName: 'demo_a',
        loginUrl: 'https://m407.mos077.com/',
        adsPowerProfileId: 'profile-a',
        adsPowerStatus: 'opened',
        bettingEnabled: true,
        status: 'success',
        balance: 900,
        currency: 'CNY',
        lastCheckedAt: Date.now(),
      },
    ])

    render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    fireEvent.click(screen.getByText('滚球'))

    await waitFor(() => {
      expect(screen.getAllByText('正在投注盘口').length).toBeGreaterThan(0)
      expect(screen.getAllByText('罗马 vs 拉齐奥').length).toBeGreaterThan(0)
      expect(screen.getAllByText('大小球 2.5').length).toBeGreaterThan(0)
      expect(screen.getAllByText('大球').length).toBeGreaterThan(0)
      expect(screen.getByText('队列 #1')).toBeTruthy()
    })
  }, 30000)

  it.each([2, 5, 8, 10])('executes %i eligible accounts one by one instead of in parallel', async (accountCount) => {
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'prematch',
      autoEnabled: true,
      perAccountLimit: 50,
      betLimit: 500,
      minimumBetOdds: 0.70,
    }))
    writeAccounts(Array.from({ length: accountCount }, (_, index) => ({
      id: `account-${index + 1}`,
      displayName: `Account ${index + 1}`,
      loginName: `demo_${index + 1}`,
      loginUrl: 'https://m407.mos077.com/',
      adsPowerProfileId: `profile-${index + 1}`,
      adsPowerStatus: 'opened',
      bettingEnabled: true,
      status: 'success',
      balance: 1000,
      currency: 'CNY',
      lastCheckedAt: Date.now(),
    })))
    mockApiState.alerts = [
      {
        id: 1201,
        alertType: 'odds_change',
        severity: 'info',
        matchName: '1201',
        title: '赔率变动：曼城 vs 利物浦',
        message: `赛前赔率变动

联赛：英超
比赛：曼城 vs 利物浦

盘口：让球 主队 -0.5
平博：0.91 -> 0.95
皇冠：0.90 -> 0.94

筛选：动水通过 / 合水通过
时间：2026-05-14 16:04:10`,
        createdAt: Date.now(),
        acknowledged: false,
      },
    ]
    mockApiState.executionResults = Array.from({ length: accountCount }, (_, index) => ({
        status: 'placed',
        reason: 'crown_history_verified',
        crownHistoryVerified: true,
        crownBetReference: `CROWN-${index + 1}`,
        delayMs: 20,
    }))

    render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    await waitFor(() => {
      expect(screen.getByText('当前账号')).toBeTruthy()
      expect(screen.getAllByText(/Account/).length).toBeGreaterThan(0)
    }, { timeout: 3000 })
    await waitFor(() => {
      const executeCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
      expect(executeCalls).toHaveLength(accountCount)
    }, { timeout: 15000 })

    expect(mockApiState.maxConcurrentExecutions).toBe(1)
  }, 45000)

  it('runs live checks from the latest crown alert rise without pinnacle', async () => {
    const alertCreatedAt = Date.now()
    mockApiState.alerts = [{ ...mockApiState.alerts[0], createdAt: alertCreatedAt }]
    render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    fireEvent.click(screen.getByText('滚球'))

    await waitFor(() => {
      expect(screen.getAllByText('米亚尔比 vs 哈马比').length).toBeGreaterThan(0)
      expect(screen.getAllByText('米亚尔比').length).toBeGreaterThan(0)
      expect(screen.getAllByText('已下注').length).toBe(2)
    })

    await waitFor(() => {
      const signalCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
      expect(signalCalls).toHaveLength(2)
    })
    const signalCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
    expect(signalCalls.every(([, body]) => (
      body.bettingMode === 'live' &&
      body.matchPhase === 'live' &&
      body.matchTitle === '米亚尔比 vs 哈马比' &&
        body.marketType === 'handicap' &&
        body.lineValue === '0/0.5' &&
        body.selectionName === '米亚尔比' &&
        body.referenceSourceKey === 'crown' &&
        body.targetSourceKey === 'crown' &&
        body.referenceOdds === 0.73 &&
        body.targetOdds === 0.76 &&
        body.capturedAt === alertCreatedAt &&
        body.maxSignalAgeSeconds === 360
    ))).toBe(true)
    const executeCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
    expect(executeCalls).toHaveLength(2)
    expect(executeCalls.every(([, body]) => body.minimumTargetOdds === 0.76)).toBe(true)
  }, 30000)

  it('checks enabled account login before sending one betting signal', async () => {
    writeAccounts([
      {
        id: 'account-a',
        displayName: 'Enabled account',
        loginName: 'demo_a',
        loginUrl: 'https://m407.mos077.com/',
        adsPowerProfileId: 'profile-a',
        adsPowerStatus: 'opened',
        bettingEnabled: true,
        status: 'unchecked',
        balance: null,
        currency: 'CNY',
        lastCheckedAt: Date.now(),
      },
      {
        id: 'account-b',
        displayName: 'Disabled account',
        loginName: 'demo_b',
        loginUrl: 'https://m407.mos077.com/',
        adsPowerProfileId: 'profile-b',
        adsPowerStatus: 'opened',
        bettingEnabled: false,
        status: 'success',
        balance: 900,
        currency: 'CNY',
        lastCheckedAt: Date.now(),
      },
    ])
    render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    fireEvent.click(screen.getByText('滚球'))

    await waitFor(() => {
      const signalCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
      expect(signalCalls).toHaveLength(1)
    })

    const calls = vi.mocked(apiClient.post).mock.calls
    const checkCalls = calls.filter(([url]) => url === '/auto-betting/adspower/crown-session/match')
    const signalCalls = calls.filter(([url]) => url === '/auto-betting/signals/odds-monitor')
    const executeCalls = calls.filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
    const firstCheckIndex = calls.findIndex(([url]) => url === '/auto-betting/adspower/crown-session/match')
    const firstSignalIndex = calls.findIndex(([url]) => url === '/auto-betting/signals/odds-monitor')
    const firstExecuteIndex = calls.findIndex(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))

    expect(checkCalls).toHaveLength(1)
    expect(checkCalls[0][1]).toEqual(expect.objectContaining({ loginName: 'demo_a', preferredProfileId: 'profile-a' }))
    expect(signalCalls[0][1]).toEqual(expect.objectContaining({ accountKey: 'account-a', stakeAmount: 50 }))
    expect(executeCalls).toHaveLength(1)
    expect(executeCalls[0][1]).toEqual(expect.objectContaining({
      profileId: 'matched-profile',
      minimumTargetOdds: 0.76,
    }))
    expect(firstCheckIndex).toBeGreaterThan(-1)
    expect(firstSignalIndex).toBeGreaterThan(firstCheckIndex)
    expect(firstExecuteIndex).toBeGreaterThan(firstSignalIndex)
  }, 30000)

  it('does not restart automatic polling when account state changes during execution', async () => {
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'live',
      autoEnabled: true,
      perAccountLimit: 50,
      betLimit: 100,
      minimumBetOdds: 0.70,
    }))

    render(<CrownBetting />)

    await waitFor(() => {
      const executeCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
      expect(executeCalls).toHaveLength(2)
    })

    const alertCallsAfterExecution = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => url === '/odds-monitor/alerts/list')
      .length
    expect(alertCallsAfterExecution).toBe(1)

    await new Promise((resolve) => setTimeout(resolve, 100))
    const alertCallsAfterStateUpdates = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => url === '/odds-monitor/alerts/list')
      .length
    expect(alertCallsAfterStateUpdates).toBe(1)
  }, 30000)

  it('keeps the placed result visible when the original signal expires during crown execution', async () => {
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'live',
      autoEnabled: true,
      perAccountLimit: 50,
      betLimit: 50,
      minimumBetOdds: 0.70,
      signalMaxAgeSeconds: 3,
    }))
    writeAccounts([
      {
        id: 'account-a',
        displayName: '投注账号',
        loginName: 'demo_a',
        loginUrl: 'https://m407.mos077.com/',
        adsPowerProfileId: 'profile-a',
        adsPowerStatus: 'opened',
        bettingEnabled: true,
        status: 'success',
        balance: 900,
        currency: 'CNY',
        lastCheckedAt: Date.now(),
      },
    ])
    mockApiState.alerts = [
      {
        id: 1901,
        alertType: 'odds_change',
        severity: 'info',
        matchName: '1901',
        title: '赔率变动：米亚尔比 vs 哈马比',
        message: `滚球赔率变动

联赛：瑞典杯
比赛：米亚尔比 vs 哈马比
进行：第 2 分钟
比分：2-1

盘口：让球 主队 0/0.5
皇冠：0.73 -> 0.76

筛选：动水通过 / 合水通过
时间：2026-05-14 16:04:10`,
        createdAt: Date.now(),
        acknowledged: false,
      },
    ]
    mockApiState.executionResults = [
      {
        status: 'placed',
        reason: 'crown_history_verified',
        crownHistoryVerified: true,
        crownBetReference: 'CROWN-STABLE-1901',
        delayMs: 5500,
      },
    ]

    render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    await waitFor(() => {
      const executeCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
      expect(executeCalls).toHaveLength(1)
    }, { timeout: 7000 })
    await screen.findByText(/CROWN-STABLE-1901/, {}, { timeout: 10000 })
    await new Promise((resolve) => setTimeout(resolve, 5500))

    expect(screen.getByText(/CROWN-STABLE-1901/)).toBeTruthy()
    const alertCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => url === '/odds-monitor/alerts/list')
    expect(alertCalls).toHaveLength(2)
  }, 30000)

  it('retries the same alert after a failed crown execution', async () => {
    const originalNow = Date.now()
    let nowSpy: ReturnType<typeof vi.spyOn> | null = null
    try {
      window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
        autoMode: 'live',
        autoEnabled: true,
        perAccountLimit: 50,
        betLimit: 50,
        minimumBetOdds: 0.70,
        signalMaxAgeSeconds: 360,
      }))
      mockApiState.alerts = [
        {
          id: 2001,
          alertType: 'odds_change',
          severity: 'info',
          matchName: '2001',
          title: '赔率变动：米亚尔比 vs 哈马比',
          message: `滚球赔率变动

联赛：瑞典杯
比赛：米亚尔比 vs 哈马比
进行：第 2 分钟
比分：2-1

盘口：让球 主队 0/0.5
皇冠：0.73 -> 0.76

筛选：动水通过 / 合水通过
时间：2026-05-14 16:04:10`,
          createdAt: Date.now(),
          acknowledged: false,
        },
      ]
      writeAccounts([
        {
          id: 'account-a',
          displayName: 'Enabled account',
          loginName: 'demo_a',
          loginUrl: 'https://m407.mos077.com/',
          adsPowerProfileId: 'profile-a',
          adsPowerStatus: 'opened',
          bettingEnabled: true,
          status: 'success',
          balance: 900,
          currency: 'CNY',
          lastCheckedAt: Date.now(),
        },
      ])
      mockApiState.executionResults = [
        {
          status: 'rejected',
          reason: 'crown_execution_timeout',
          crownHistoryVerified: false,
          crownBetReference: null,
        },
        {
          status: 'placed',
          reason: 'crown_history_verified',
          crownHistoryVerified: true,
          crownBetReference: 'CROWN-20001',
        },
      ]

      render(<CrownBetting />)

      await screen.findByText('自动化接入投注功能')
      await waitFor(() => {
        const executeCalls = vi.mocked(apiClient.post).mock.calls
          .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
        expect(executeCalls).toHaveLength(1)
      })

      nowSpy = vi.spyOn(Date, 'now').mockReturnValue(originalNow + 6_000)
      const amountInputs = screen.getAllByRole('spinbutton') as HTMLInputElement[]
      fireEvent.change(amountInputs[3], { target: { value: '601' } })

      await waitFor(() => {
        const executeCalls = vi.mocked(apiClient.post).mock.calls
          .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
        expect(executeCalls).toHaveLength(2)
      }, { timeout: 1000 })
      expect(screen.getByText(/CROWN-20001/)).toBeTruthy()
    } finally {
      nowSpy?.mockRestore()
    }
  }, 30000)

  it('stops retrying the same crown signal when the market is locked', async () => {
    const originalNow = Date.now()
    let nowSpy: ReturnType<typeof vi.spyOn> | null = null
    try {
      window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
        autoMode: 'live',
        autoEnabled: true,
        perAccountLimit: 50,
        betLimit: 50,
        minimumBetOdds: 0.70,
        signalMaxAgeSeconds: 360,
      }))
      mockApiState.alerts = [
        {
          id: 2101,
          alertType: 'odds_change',
          severity: 'info',
          matchName: '2101',
          title: '赔率变动：米亚尔比 vs 哈马比',
          message: `滚球赔率变动

联赛：瑞典杯
比赛：米亚尔比 vs 哈马比
进行：第 2 分钟
比分：2-1

盘口：让球 主队 0/0.5
皇冠：0.73 -> 0.76

筛选：动水通过 / 合水通过
时间：2026-05-14 16:04:10`,
          createdAt: Date.now(),
          acknowledged: false,
        },
      ]
      writeAccounts([
        {
          id: 'account-a',
          displayName: 'Enabled account',
          loginName: 'demo_a',
          loginUrl: 'https://m407.mos077.com/',
          adsPowerProfileId: 'profile-a',
          adsPowerStatus: 'opened',
          bettingEnabled: true,
          status: 'success',
          balance: 900,
          currency: 'CNY',
          lastCheckedAt: Date.now(),
        },
      ])
      mockApiState.executionResults = [
        {
          status: 'rejected',
          reason: 'crown_market_locked',
          crownHistoryVerified: false,
          crownBetReference: null,
        },
        {
          status: 'rejected',
          reason: 'crown_market_locked',
          crownHistoryVerified: false,
          crownBetReference: null,
        },
      ]

      render(<CrownBetting />)

      await screen.findByText('自动化接入投注功能')
      await waitFor(() => {
        const executeCalls = vi.mocked(apiClient.post).mock.calls
          .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
        expect(executeCalls).toHaveLength(1)
      })

      nowSpy = vi.spyOn(Date, 'now').mockReturnValue(originalNow + 6_000)
      const amountInputs = screen.getAllByRole('spinbutton') as HTMLInputElement[]
      fireEvent.change(amountInputs[3], { target: { value: '601' } })

      await new Promise((resolve) => setTimeout(resolve, 100))
      const executeCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
      expect(executeCalls).toHaveLength(1)
    } finally {
      nowSpy?.mockRestore()
    }
  }, 30000)

  it('uses the signal target water as the execution water floor', async () => {
    render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    fireEvent.click(screen.getByText('滚球'))

    await waitFor(() => {
      const executeCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
      expect(executeCalls).toHaveLength(2)
    })
    const executeCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
    expect(executeCalls.every(([, body]) => body.minimumTargetOdds === 0.76)).toBe(true)
  }, 30000)

  it('syncs the betting page mode from the active monitor mode before consuming alerts', async () => {
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'prematch',
      autoEnabled: true,
      perAccountLimit: 50,
      betLimit: 100,
      minimumBetOdds: 0.70,
      signalMaxAgeSeconds: 360,
    }))
    mockApiState.notificationConfigs = [
      {
        id: 2,
        name: '全平台赔率监控TG',
        type: 'telegram',
        enabled: true,
        config: {
          data: {
            monitorModeEnabled: true,
            liveOnlyModeEnabled: true,
          },
        },
      },
    ]
    mockApiState.alerts = [
      {
        id: 1501,
        alertType: 'odds_change',
        severity: 'info',
        title: '赔率变动：罗马 vs 拉齐奥',
        message: `滚球赔率变动

联赛：意甲
比赛：罗马 vs 拉齐奥
进行：第 12 分钟
比分：0-0

盘口：大小球 大球 2.5
皇冠：0.94 -> 0.98

筛选：动水通过 / 合水通过
时间：2026-05-21 22:12:10`,
        createdAt: Date.now(),
        acknowledged: false,
      },
    ]

    render(<CrownBetting />)

    await waitFor(() => {
      const signalCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
      expect(signalCalls.length).toBeGreaterThan(0)
      expect(signalCalls.every(([, body]) => body.matchPhase === 'live')).toBe(true)
    })
    expect(screen.getAllByText('罗马 vs 拉齐奥').length).toBeGreaterThan(0)
    expect(window.localStorage.getItem('crown-betting-automation-settings')).toContain('"autoMode":"live"')
  }, 30000)

  it('blocks execution when no crown alert matches the selected mode', async () => {
    render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    expect(screen.queryByRole('button', { name: /运行实际测试/ })).toBeNull()

    await waitFor(() => {
      expect(screen.getAllByText('暂无可执行监控信号').length).toBeGreaterThan(0)
    })
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      url === '/auto-betting/signals/odds-monitor'
    ))).toBe(false)
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url))
    ))).toBe(false)
  }, 30000)

  it('keeps showing prematch crown signals when all accounts are not enabled for betting', async () => {
    writeAccounts([
      {
        id: 'account-a',
        displayName: '投注主账号',
        loginName: 'demo_a',
        loginUrl: 'https://m407.mos077.com/',
        adsPowerProfileId: 'profile-a',
        adsPowerStatus: 'opened',
        bettingEnabled: false,
        status: 'success',
        balance: 1200,
        currency: 'CNY',
        lastCheckedAt: Date.now(),
      },
      {
        id: 'account-b',
        displayName: '投注副账号',
        loginName: 'demo_b',
        loginUrl: 'https://m407.mos077.com/',
        adsPowerProfileId: 'profile-b',
        adsPowerStatus: 'opened',
        bettingEnabled: false,
        status: 'success',
        balance: 900,
        currency: 'CNY',
        lastCheckedAt: Date.now(),
      },
    ])
    mockApiState.alerts = [
      {
        id: 1401,
        alertType: 'odds_change',
        severity: 'info',
        title: '赔率变动：曼城 vs 利物浦',
        message: `赛前赔率变动

联赛：英超
比赛：曼城 vs 利物浦

盘口：让球 主队 -0.5
皇冠：1.05 -> 1.08

筛选：动水通过 / 合水通过
时间：2026-05-19 20:10:10`,
        createdAt: Date.now(),
        acknowledged: false,
      },
    ]

    render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    await waitFor(() => {
      expect(screen.getAllByText('曼城 vs 利物浦').length).toBeGreaterThan(0)
      expect(screen.getAllByText('没有可用账号').length).toBeGreaterThan(0)
    })
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      url === '/odds-monitor/alerts/list'
    ))).toBe(true)
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      url === '/auto-betting/signals/odds-monitor'
    ))).toBe(false)
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url))
    ))).toBe(false)
  }, 30000)

  it('shows crown alert candidates but does not execute when automatic betting switch is closed', async () => {
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'live',
      autoEnabled: false,
      perAccountLimit: 50,
      betLimit: 100,
      minimumBetOdds: 0.70,
    }))

    render(<CrownBetting />)

    await screen.findByText('自动投注已关闭')
    await waitFor(() => {
      expect(screen.getAllByText('米亚尔比 vs 哈马比').length).toBeGreaterThan(0)
    })
    expect(screen.getByText('采集系统合格回传')).toBeTruthy()
    expect(screen.getByText('#1')).toBeTruthy()
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      url === '/odds-monitor/alerts/list'
    ))).toBe(true)
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      url === '/auto-betting/signals/odds-monitor'
    ))).toBe(false)
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url))
    ))).toBe(false)
  }, 30000)

  it('hides crown alert candidates below the configured minimum target water', async () => {
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'live',
      autoEnabled: true,
      perAccountLimit: 50,
      betLimit: 100,
      minimumBetOdds: 1.20,
    }))

    render(<CrownBetting />)

    await waitFor(() => {
      expect(screen.getAllByText('暂无符合配置的候选盘口').length).toBeGreaterThan(0)
    })
    expect(screen.queryByText('米亚尔比 vs 哈马比')).toBeNull()
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      url === '/auto-betting/signals/odds-monitor'
    ))).toBe(false)
  }, 30000)

  it('executes a crown alert by minimum target water without requiring edge', async () => {
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'live',
      autoEnabled: true,
      perAccountLimit: 50,
      betLimit: 100,
      minimumBetOdds: 1.01,
      signalMaxAgeSeconds: 360,
    }))
    mockApiState.alerts = [
      {
        id: 1301,
        alertType: 'odds_change',
        severity: 'info',
        title: '赔率变动：伯恩茅斯 vs 曼城',
        message: `滚球赔率变动

联赛：英格兰超级联赛
比赛：伯恩茅斯 vs 曼城
进行：第 35 分钟
比分：0-0

盘口：让球 客队 1
皇冠：1.07 -> 1.08

筛选：动水通过 / 合水通过
时间：2026-05-19 20:10:10`,
        createdAt: Date.now(),
        acknowledged: false,
      },
    ]

    render(<CrownBetting />)

    await waitFor(() => {
      expect(screen.getAllByText('伯恩茅斯 vs 曼城').length).toBeGreaterThan(0)
    })
    await waitFor(() => {
      const signalCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
        .slice(-2)
      expect(signalCalls).toHaveLength(2)
    })
    const signalCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
      .slice(-2)
    expect(signalCalls.every(([, body]) => (
      body.matchTitle === '伯恩茅斯 vs 曼城' &&
      body.targetOdds === 1.08 &&
      body.minimumTargetOdds === 1.01
    ))).toBe(true)
    const executeCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url)))
    expect(executeCalls).toHaveLength(2)
    expect(executeCalls.every(([, body]) => body.minimumTargetOdds === 1.08)).toBe(true)
    expect(executeCalls.every(([, , config]) => config?.timeout === 30000)).toBe(true)
  }, 30000)

  it('does not send expired crown alerts to backend betting decisions', async () => {
    const now = Date.now()
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'live',
      autoEnabled: true,
      perAccountLimit: 50,
      betLimit: 100,
      minimumBetOdds: 0.70,
      signalMaxAgeSeconds: 360,
    }))
    mockApiState.alerts = [
      {
        id: 1201,
        alertType: 'odds_change',
        severity: 'info',
        title: '赔率变动：赫尔辛堡 vs 华保斯',
        message: `滚球赔率变动

联赛：瑞典超级甲组联赛
比赛：赫尔辛堡 vs 华保斯
进行：第 53 分钟
比分：1-2

盘口：让球 主队 0
皇冠：1.15 -> 1.19

筛选：动水通过 / 合水通过
时间：2026-05-19 19:09:54`,
        createdAt: now - 601_000,
        acknowledged: false,
      },
    ]

    render(<CrownBetting />)

    await waitFor(() => {
      expect(screen.getAllByText('暂无符合配置的候选盘口').length).toBeGreaterThan(0)
    })
    const decisionCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
    expect(decisionCalls).toHaveLength(0)
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      url === '/auto-betting/signals/odds-monitor'
    ))).toBe(false)
  }, 30000)

  it('uses the newest qualified alert inside signal validity when the latest alert is below settings', async () => {
    const now = Date.now()
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'live',
      autoEnabled: true,
      perAccountLimit: 50,
      betLimit: 100,
      minimumBetOdds: 1.01,
      signalMaxAgeSeconds: 360,
    }))
    mockApiState.alerts = [
      {
        id: 1101,
        alertType: 'odds_change',
        severity: 'info',
        title: '赔率变动：奥尔格里特 vs IFK哥德堡',
        message: `滚球赔率变动

联赛：瑞典超级联赛
比赛：奥尔格里特 vs IFK哥德堡
进行：第 2 分钟
比分：1-2

盘口：大小球 大球 3.5/4
皇冠：0.77 -> 0.88

筛选：动水通过 / 合水通过
时间：2026-05-19 14:42:50`,
        createdAt: now,
        acknowledged: false,
      },
      {
        id: 1100,
        alertType: 'odds_change',
        severity: 'info',
        title: '赔率变动：奥尔格里特 vs IFK哥德堡',
        message: `滚球赔率变动

联赛：瑞典超级联赛
比赛：奥尔格里特 vs IFK哥德堡
进行：第 2 分钟
比分：1-2

盘口：大小球 大球 4
皇冠：1.04 -> 1.11

筛选：动水通过 / 合水通过
时间：2026-05-19 14:39:33`,
        createdAt: now - 180_000,
        acknowledged: false,
      },
    ]

    render(<CrownBetting />)

    await waitFor(() => {
      expect(screen.getAllByText('奥尔格里特 vs IFK哥德堡').length).toBeGreaterThan(0)
      expect(screen.getAllByText('大球').length).toBeGreaterThan(0)
    })
    await waitFor(() => {
      const signalCalls = vi.mocked(apiClient.post).mock.calls
        .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
      expect(signalCalls).toHaveLength(2)
    })
    const signalCalls = vi.mocked(apiClient.post).mock.calls
      .filter(([url]) => url === '/auto-betting/signals/odds-monitor')
    expect(signalCalls.every(([, body]) => (
      body.matchTitle === '奥尔格里特 vs IFK哥德堡' &&
      body.marketType === 'total' &&
      body.lineValue === '4' &&
      body.selectionName === '大球' &&
      body.referenceOdds === 1.04 &&
      body.targetOdds === 1.11
    ))).toBe(true)
  }, 30000)

  it('saves automation amount settings across a reload', async () => {
    const { unmount } = render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    const amountInputs = screen.getAllByRole('spinbutton') as HTMLInputElement[]
    fireEvent.change(amountInputs[0], { target: { value: '420' } })
    fireEvent.change(amountInputs[1], { target: { value: '880' } })
    fireEvent.change(amountInputs[2], { target: { value: '0.70' } })
    fireEvent.change(amountInputs[3], { target: { value: '900' } })
    fireEvent.click(screen.getByRole('button', { name: /保存设置/ }))

    expect(window.localStorage.getItem('crown-betting-automation-settings')).toContain('"perAccountLimit":420')
    expect(window.localStorage.getItem('crown-betting-automation-settings')).toContain('"betLimit":880')
    expect(window.localStorage.getItem('crown-betting-automation-settings')).toContain('"minimumBetOdds":0.7')
    expect(window.localStorage.getItem('crown-betting-automation-settings')).toContain('"signalMaxAgeSeconds":900')
    expect(window.localStorage.getItem('crown-betting-automation-settings')).not.toContain('oddsTolerance')
    expect(window.localStorage.getItem('crown-betting-automation-settings')).not.toContain('minimumEdge')

    unmount()
    render(<CrownBetting />)

    const reloadedInputs = screen.getAllByRole('spinbutton') as HTMLInputElement[]
    expect(reloadedInputs[0].value).toBe('420')
    expect(reloadedInputs[1].value).toBe('880')
    expect(reloadedInputs[2].value).toBe('0.70')
    expect(reloadedInputs[3].value).toBe('900')
  }, 30000)

  it('defaults betting amount controls to 50 per account and 100 total', async () => {
    render(<CrownBetting />)

    await screen.findByText('自动化接入投注功能')
    const amountInputs = screen.getAllByRole('spinbutton') as HTMLInputElement[]

    expect(amountInputs[0].value).toBe('50')
    expect(amountInputs[1].value).toBe('100')
  })

  it('checks bound AdsPower crown login status and balance instead of running local direct login', async () => {
    writeAccounts([
      {
        id: 'bound-account',
        displayName: 'Bound Profile',
        loginName: 'bound_login',
        loginUrl: 'https://m407.mos077.com/',
        adsPowerProfileId: 'profile-active',
        adsPowerStatus: 'unlinked',
        status: 'unchecked',
        balance: null,
        currency: 'CNY',
        lastCheckedAt: Date.now(),
      },
    ])
    mockApiState.crownSession = {
      profileId: 'profile-active',
      opened: true,
      loggedIn: true,
      accountStatus: 'online',
      balance: 2000,
      currency: 'CNY',
      message: '账号在线，余额已获取',
      debugPort: '39555',
      checkedAt: 1234,
    }

    render(<CrownBetting />)

    fireEvent.click(screen.getAllByRole('button', { name: /检测一次/ })[0])

    await screen.findByText(/账号在线，余额已获取/)
    await screen.findByText(/2,000.00/)
    await screen.findByText('在线')
    expect(apiClient.post).toHaveBeenCalledWith('/auto-betting/adspower/crown-session/match', {
      loginName: 'bound_login',
      loginUrl: 'https://m407.mos077.com/',
      preferredProfileId: 'profile-active',
    })
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      url === '/odds-monitor/crown/accounts/check'
    ))).toBe(false)
  })

  it('does not collect betting account passwords in the add account dialog', async () => {
    render(<CrownBetting />)

    fireEvent.click(screen.getByRole('button', { name: /添加皇冠账号/ }))

    await screen.findByText('AdsPower 档案 ID / 编号')
    expect(screen.getByPlaceholderText(/左侧编号/)).toBeTruthy()
    expect(screen.queryByText('登录密码')).toBeNull()
    expect(screen.getByDisplayValue('https://m407.mos077.com/')).toBeTruthy()
  }, 30000)
})
