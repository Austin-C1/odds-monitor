import { describe, expect, it } from 'vitest'
import {
  buildAutoBettingExecutionPlan,
  extractCrownAlertSignalCandidates,
  extractLatestCrownAlertSignal,
  extractLatestMonitorSignal,
  type AutoBettingSignal,
} from './crownBettingExecutionPlan'

const prematchSignal: AutoBettingSignal = {
  modeLabel: '赛前',
  bettingMode: 'prematch',
  matchPhase: 'prematch',
  leagueName: '英超',
  matchTitle: '曼城 vs 利物浦',
  marketType: 'handicap',
  marketTitle: '让球盘 -0.5',
  lineValue: '-0.5',
  selectionName: '曼城',
  referenceSourceKey: 'pinnacle',
  targetSourceKey: 'crown',
  referenceOdds: 1.92,
  targetOdds: 0.95,
  odds: 0.95,
  edge: 0.03,
  bettingLogic: 'Pinnacle reference advantage versus Crown target odds',
}

describe('crown betting execution plan', () => {
  it('reverses a rising crown side to the opposite dropping side from a live alert record', () => {
    const signal = extractLatestCrownAlertSignal([
      {
        id: 1001,
        title: '赔率变动：米亚尔比 vs 哈马比',
        message: `滚球赔率变动

联赛：瑞典杯
比赛：米亚尔比 vs 哈马比
进行：第 2 分钟
比分：2-1

盘口：让球 主队 0/0.5
皇冠：0.73 -> 0.76

盘口：让球 客队 0/0.5
皇冠：1.15 -> 1.12

筛选：动水通过 / 合水通过
时间：2026-05-14 16:04:10`,
        createdAt: Date.now(),
      },
    ], 'live')

    expect(signal).toEqual(expect.objectContaining({
      bettingMode: 'live',
      matchPhase: 'live',
      leagueName: '瑞典杯',
      matchTitle: '米亚尔比 vs 哈马比',
      marketType: 'handicap',
      marketTitle: '让球 0/0.5',
      lineValue: '0/0.5',
      selectionName: '哈马比',
      referenceSourceKey: 'crown',
      targetSourceKey: 'crown',
      referenceOdds: 1.15,
      targetOdds: 1.12,
      edge: 0.03,
      bettingLogic: expect.stringContaining('reverse'),
    }))
  })

  it('lists crown alert signal candidates with market and odds movement', () => {
    const candidates = extractCrownAlertSignalCandidates([
      {
        id: 1001,
        title: '赔率变动：米亚尔比 vs 哈马比',
        message: `滚球赔率变动

联赛：瑞典杯
比赛：米亚尔比 vs 哈马比
进行：第 2 分钟
比分：0-1

盘口：让球 主队 0/0.5
皇冠：0.73 -> 0.76

盘口：让球 客队 0/0.5
皇冠：1.15 -> 1.12

盘口：大小球 大球 3
皇冠：0.77 -> 0.85

盘口：大小球 小球 3
皇冠：0.93 -> 0.85

筛选：动水通过 / 合水通过
时间：2026-05-14 16:04:10`,
        createdAt: Date.now(),
      },
    ], 'live')

    expect(candidates).toEqual([
      expect.objectContaining({
        matchTitle: '米亚尔比 vs 哈马比',
        marketTitle: '让球 0/0.5',
        selectionName: '哈马比',
        referenceOdds: 1.15,
        targetOdds: 1.12,
        edge: 0.03,
      }),
      expect.objectContaining({
        matchTitle: '米亚尔比 vs 哈马比',
        marketTitle: '大小球 3',
        selectionName: '小球',
        referenceOdds: 0.93,
        targetOdds: 0.85,
        edge: 0.08,
      }),
    ])
  })

  it('extracts a prematch signal from the selected monitor match', () => {
    const signal = extractLatestMonitorSignal({
      matches: [],
      selectedMatch: {
        match: {
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
        metrics: [
          { label: 'handicap home -0.5', value: '1.92', trend: 'stable', sourceKey: 'pinnacle' },
          { label: 'handicap home -0.5', value: '0.95', trend: 'stable', sourceKey: 'crown' },
        ],
        oddsHistory: [],
        platformMatches: [],
      },
    })

    expect(signal).toEqual(expect.objectContaining({
      bettingMode: 'prematch',
      matchPhase: 'prematch',
      leagueName: '英超',
      matchTitle: '曼城 vs 利物浦',
      marketType: 'handicap',
      marketTitle: '让球盘 -0.5',
      lineValue: '-0.5',
      selectionName: '曼城',
      referenceOdds: 1.92,
      targetOdds: 0.95,
      edge: 0.03,
    }))
  })

  it('extracts a live total signal from the selected monitor match', () => {
    const signal = extractLatestMonitorSignal({
      matches: [],
      selectedMatch: {
        match: {
          id: 2,
          leagueName: '意甲',
          homeTeam: '罗马',
          awayTeam: '拉齐奥',
          startTime: Date.now() - 60_000,
          status: 'live',
          sourceCount: 2,
          alertCount: 0,
          matchedPlatforms: ['pinnacle', 'crown'],
        },
        metrics: [
          { label: 'total over 2.5', value: '1.94', trend: 'up', sourceKey: 'pinnacle' },
          { label: 'total over 2.5', value: '0.98', trend: 'up', sourceKey: 'crown' },
        ],
        oddsHistory: [],
        platformMatches: [],
      },
    })

    expect(signal).toEqual(expect.objectContaining({
      bettingMode: 'live',
      matchPhase: 'live',
      matchTitle: '罗马 vs 拉齐奥',
      marketType: 'total',
      marketTitle: '大小球 2.5',
      selectionName: '大球',
      referenceOdds: 1.94,
      targetOdds: 0.98,
      edge: 0.04,
    }))
  })

  it('splits stake using the supplied monitor signal and skips abnormal accounts', () => {
    const result = buildAutoBettingExecutionPlan({
      signal: prematchSignal,
      mode: 'prematch',
      enabled: true,
      perAccountLimit: 300,
      betLimit: 500,
      minimumEdge: 0.03,
      minimumBetOdds: 0.7,
      oddsTolerance: 0.02,
      accounts: [
        { id: 'a', displayName: '主账号', status: 'success', adsPowerProfileId: 'profile-a', adsPowerStatus: 'opened' },
        { id: 'b', displayName: '副账号', status: 'success', adsPowerProfileId: 'profile-b', adsPowerStatus: 'opened' },
        { id: 'c', displayName: '异常账号', status: 'error' },
      ],
    })

    expect(result.canExecute).toBe(true)
    expect(result.modeLabel).toBe('赛前')
    expect(result.totalStake).toBe(500)
    expect(result.availableAccountCount).toBe(2)
    expect(result.rows).toEqual([
      expect.objectContaining({ accountName: '主账号', status: 'passed', matchTitle: '曼城 vs 利物浦', stakeAmount: 250 }),
      expect.objectContaining({ accountName: '副账号', status: 'passed', matchTitle: '曼城 vs 利物浦', stakeAmount: 250 }),
      expect.objectContaining({ accountName: '异常账号', status: 'skipped', stakeAmount: 0, reason: '未绑定 AdsPower Profile' }),
    ])
    expect(JSON.stringify(result)).not.toContain('阿森纳')
    expect(JSON.stringify(result)).not.toContain('国际米兰')
  })

  it('locks execution to the selected betting mode', () => {
    const result = buildAutoBettingExecutionPlan({
      signal: { ...prematchSignal, modeLabel: '滚球', bettingMode: 'live', matchPhase: 'live' },
      mode: 'prematch',
      enabled: true,
      perAccountLimit: 300,
      betLimit: 500,
      minimumEdge: 0.03,
      minimumBetOdds: 0.7,
      oddsTolerance: 0.02,
      accounts: [
        { id: 'a', displayName: '主账号', status: 'success', adsPowerProfileId: 'profile-a', adsPowerStatus: 'opened' },
      ],
    })

    expect(result.canExecute).toBe(false)
    expect(result.summary).toBe('当前监控信号是滚球，已按赛前模式跳过')
    expect(result.rows).toEqual([])
  })

  it('blocks execution until the monitor has a usable crown signal', () => {
    const result = buildAutoBettingExecutionPlan({
      signal: null,
      mode: 'prematch',
      enabled: true,
      perAccountLimit: 300,
      betLimit: 500,
      minimumEdge: 0.03,
      minimumBetOdds: 0.7,
      oddsTolerance: 0.02,
      accounts: [
        { id: 'a', displayName: '主账号', status: 'success', adsPowerProfileId: 'profile-a', adsPowerStatus: 'opened' },
      ],
    })

    expect(result.canExecute).toBe(false)
    expect(result.summary).toBe('暂无可执行监控信号')
    expect(result.rows).toEqual([])
  })

  it('skips execution when the target crown water is below the configured floor', () => {
    const result = buildAutoBettingExecutionPlan({
      signal: { ...prematchSignal, targetOdds: 0.62, odds: 0.62 },
      mode: 'prematch',
      enabled: true,
      perAccountLimit: 300,
      betLimit: 500,
      minimumEdge: 0.03,
      minimumBetOdds: 0.7,
      oddsTolerance: 0.02,
      accounts: [
        { id: 'a', displayName: '主账号', status: 'success', adsPowerProfileId: 'profile-a', adsPowerStatus: 'opened' },
      ],
    })

    expect(result.canExecute).toBe(false)
    expect(result.summary).toBe('目标水位低于最低投注水位')
    expect(result.rows).toEqual([
      expect.objectContaining({ status: 'skipped', stakeAmount: 0, reason: '目标水位低于最低投注水位' }),
    ])
  })
})
