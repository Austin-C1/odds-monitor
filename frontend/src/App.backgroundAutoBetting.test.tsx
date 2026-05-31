// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import App from './App'
import { apiClient } from './services/api'

const mockApiState = vi.hoisted(() => ({
  alerts: [] as any[],
  intentId: 7000,
  liveOnlyModeEnabled: true,
  executionResults: [] as any[],
  activeExecutions: 0,
  maxConcurrentExecutions: 0,
}))

vi.mock('./services/api', () => ({
  apiService: {
    auth: {
      checkFirstUse: vi.fn(() => Promise.resolve({ data: { code: 0, data: { isFirstUse: false } } })),
    },
  },
  apiClient: {
    post: vi.fn((url: string, data?: any) => {
      if (url === '/system/notifications/configs/list') {
        return Promise.resolve({
          data: {
            code: 0,
            data: [{
              id: 1,
              enabled: true,
              config: { monitorModeEnabled: true, liveOnlyModeEnabled: mockApiState.liveOnlyModeEnabled },
            }],
          },
        })
      }
      if (url === '/system/config/get') {
        return Promise.resolve({ data: { code: 0, data: { autoBettingEnabled: true } } })
      }
      if (url === '/odds-monitor/alerts/list') {
        return Promise.resolve({ data: { code: 0, data: mockApiState.alerts } })
      }
      if (url === '/auto-betting/adspower/crown-session/match') {
        return Promise.resolve({
          data: {
            code: 0,
            data: {
              profileId: data?.preferredProfileId || 'profile-a',
              opened: true,
              loggedIn: true,
              accountStatus: 'online',
              balance: 2000,
              currency: 'CNY',
              message: '账号在线，余额已获取',
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
              bettingMode: data?.bettingMode,
              matchPhase: data?.matchPhase,
              accountKey: data?.accountKey,
              leagueName: data?.leagueName,
              matchTitle: data?.matchTitle,
              marketType: data?.marketType,
              lineValue: data?.lineValue,
              selectionName: data?.selectionName,
              referenceSourceKey: data?.referenceSourceKey,
              targetSourceKey: data?.targetSourceKey,
              referenceOdds: data?.referenceOdds,
              targetOdds: data?.targetOdds,
              stakeAmount: data?.stakeAmount,
              capturedAt: data?.capturedAt,
              createdAt: Date.now(),
            },
          },
        })
      }
      if (url === '/auto-betting/signals/odds-monitor/execute-crown-queue') {
        mockApiState.activeExecutions = Math.max(0, mockApiState.activeExecutions) + 1
        mockApiState.maxConcurrentExecutions = Math.max(
          mockApiState.maxConcurrentExecutions,
          mockApiState.activeExecutions,
        )
        const decisions = (data?.accounts || []).map((account: any, index: number) => {
          mockApiState.intentId += 1
          const executionResult = mockApiState.executionResults.length > 0
            ? mockApiState.executionResults.shift()
            : {
                status: 'placed',
                reason: 'crown_history_verified',
                crownHistoryVerified: true,
                crownBetReference: `OU${index + 1}`,
              }
          return {
            id: mockApiState.intentId,
            status: executionResult.status,
            reason: executionResult.reason,
            accountKey: account.accountKey,
            crownHistoryVerified: executionResult.crownHistoryVerified,
            crownHistoryCheckedAt: Date.now(),
            crownBetReference: executionResult.crownBetReference,
          }
        })
        const response = { data: { code: 0, data: decisions } }
        const finish = () => {
          mockApiState.activeExecutions = Math.max(0, mockApiState.activeExecutions - 1)
          return response
        }
        const delayMs = mockApiState.executionResults[0]?.delayMs
        if (delayMs) {
          return new Promise((resolve) => setTimeout(() => resolve(finish()), delayMs))
        }
        return Promise.resolve(finish())
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
              crownBetReference: 'OU123',
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

vi.mock('./pages/BettingHistory', () => ({
  default: () => <div>下注成功页面</div>,
}))

const writeAutomationStorage = (autoMode: 'prematch' | 'live' = 'live', accounts?: any[]) => {
  window.localStorage.setItem('jwt_token', 'valid-token')
  window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
    autoMode,
    autoEnabled: true,
    perAccountLimit: 50,
    betLimit: 100,
    minimumBetOdds: 1.01,
    signalMaxAgeSeconds: 360,
  }))
  window.localStorage.setItem('crown-betting-accounts', JSON.stringify(accounts || [
    {
      id: 'account-a',
      displayName: '投注账号',
      loginName: 'demo_a',
      loginUrl: 'https://m407.mos077.com/',
      adsPowerProfileId: 'profile-a',
      adsPowerStatus: 'opened',
      bettingEnabled: true,
      status: 'success',
      balance: 2000,
      currency: 'CNY',
    },
  ]))
}

describe('App background crown betting automation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.pushState({}, '', '/betting-history')
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
    window.localStorage.clear()
    writeAutomationStorage()
    mockApiState.intentId = 7000
    mockApiState.liveOnlyModeEnabled = true
    mockApiState.executionResults = []
    mockApiState.activeExecutions = 0
    mockApiState.maxConcurrentExecutions = 0
    mockApiState.alerts = [{
      id: 5001,
      alertType: 'odds_change',
      severity: 'info',
      title: '赔率变动：埃尔夫斯堡 vs 米亚尔比',
      message: `滚球赔率变动

联赛：瑞典超级联赛
比赛：埃尔夫斯堡 vs 米亚尔比
进行：第 0 分钟
比分：0-0

盘口：大小球 小球 2/2.5
皇冠：0.92 -> 1.08

筛选：动水通过 / 合水通过
时间：2026-05-21 18:01:05`,
      createdAt: Date.now(),
      acknowledged: false,
    }]
  })

  afterEach(() => {
    cleanup()
  })

  it('keeps automatic betting running after navigating away from the crown betting page', async () => {
    render(<App />)

    await screen.findByText('下注成功页面')

    await waitFor(() => {
      expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
        url === '/auto-betting/signals/odds-monitor/execute-crown-queue'
      ))).toBe(true)
    })
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url))
    ))).toBe(false)
    const queueCall = vi.mocked(apiClient.post).mock.calls.find(([url]) => (
      url === '/auto-betting/signals/odds-monitor/execute-crown-queue'
    ))
    expect(queueCall?.[2]?.timeout).toBe(35000)
  })

  it('keeps prematch automatic betting running after navigating away from the crown betting page', async () => {
    window.localStorage.clear()
    writeAutomationStorage('prematch')
    mockApiState.liveOnlyModeEnabled = false
    mockApiState.alerts = [{
      id: 5002,
      alertType: 'odds_change',
      severity: 'info',
      title: '赔率变动：阿尔菲斯 vs 纳加马安萘哉',
      message: `赛前赔率变动

联赛：沙特超级联赛
比赛：阿尔菲斯 vs 纳加马安萘哉

盘口：让球 阿尔菲斯 0/0.5
皇冠：0.99 -> 1.09

筛选：动水通过 / 合水通过
时间：2026-05-21 18:01:05`,
      createdAt: Date.now(),
      acknowledged: false,
    }]

    render(<App />)

    await screen.findByText('下注成功页面')

    await waitFor(() => {
      expect(vi.mocked(apiClient.post).mock.calls.some(([url, body]) => (
        url === '/auto-betting/signals/odds-monitor/execute-crown-queue' &&
        body?.bettingMode === 'prematch' &&
        body?.matchPhase === 'prematch'
      ))).toBe(true)
    })
    expect(vi.mocked(apiClient.post).mock.calls.some(([url]) => (
      /^\/auto-betting\/intents\/\d+\/execute-crown$/.test(String(url))
    ))).toBe(false)
  })

  it('runs background account executions one by one for ten accounts', async () => {
    window.localStorage.clear()
    writeAutomationStorage('live', Array.from({ length: 10 }, (_, index) => ({
      id: `account-${index + 1}`,
      displayName: `Account ${index + 1}`,
      loginName: `demo_${index + 1}`,
      loginUrl: 'https://m407.mos077.com/',
      adsPowerProfileId: `profile-${index + 1}`,
      adsPowerStatus: 'opened',
      bettingEnabled: true,
      status: 'success',
      balance: 2000,
      currency: 'CNY',
    })))
    window.localStorage.setItem('crown-betting-automation-settings', JSON.stringify({
      autoMode: 'live',
      autoEnabled: true,
      perAccountLimit: 50,
      betLimit: 500,
      minimumBetOdds: 1.01,
      signalMaxAgeSeconds: 360,
    }))
    mockApiState.executionResults = Array.from({ length: 10 }, (_, index) => ({
      status: 'placed',
      reason: 'crown_history_verified',
      crownHistoryVerified: true,
      crownBetReference: `BG-${index + 1}`,
      delayMs: 20,
    }))

    render(<App />)

    await waitFor(() => {
      const queueCalls = vi.mocked(apiClient.post).mock.calls.filter(([url]) => (
        url === '/auto-betting/signals/odds-monitor/execute-crown-queue'
      ))
      expect(queueCalls).toHaveLength(1)
    }, { timeout: 5000 })

    expect(mockApiState.maxConcurrentExecutions).toBe(1)
    const queueCalls = vi.mocked(apiClient.post).mock.calls.filter(([url]) => (
      url === '/auto-betting/signals/odds-monitor/execute-crown-queue'
    ))
    expect(queueCalls[0]?.[1]?.accounts).toHaveLength(10)
    expect(queueCalls[0]?.[2]?.timeout).toBe(305000)
  })
})
