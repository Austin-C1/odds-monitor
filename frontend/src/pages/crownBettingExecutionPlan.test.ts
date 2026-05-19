import { describe, expect, it } from 'vitest'
import {
  buildAutoBettingExecutionPlan,
  extractCrownAlertSignalCandidates,
  extractLatestCrownAlertSignal,
  filterLatestCrownAlertSignalBatch,
  formatAutoBettingReason,
  type AutoBettingSignal,
} from './crownBettingExecutionPlan'

const prematchSignal: AutoBettingSignal = {
  modeLabel: '赛前',
  bettingMode: 'prematch',
  matchPhase: 'prematch',
  leagueName: '英超',
  matchTitle: '曼城 vs 利物浦',
  marketType: 'handicap',
  marketTitle: '让球 -0.5',
  lineValue: '-0.5',
  selectionName: '曼城',
  referenceSourceKey: 'crown',
  targetSourceKey: 'crown',
  referenceOdds: 0.90,
  targetOdds: 0.94,
  odds: 0.94,
  edge: 0.04,
  oddsChangeDirection: 'rise',
  bettingLogic: 'telegram: 曼城 0.9 -> 0.94',
}

describe('crown betting execution plan', () => {
  it('creates a usable signal from a single crown line in a telegram alert', () => {
    const candidates = extractCrownAlertSignalCandidates([
      {
        id: 1003,
        title: '赔率变动：深圳新鹏城 vs 大连英博',
        message: `滚球赔率变动

联赛：中国超级联赛
比赛：深圳新鹏城 vs 大连英博
进行：第 1 分钟
比分：0-0

盘口：大小球 大球 2/2.5
皇冠：0.82 -> 0.97

筛选：动水通过 / 合水通过
时间：2026-05-19 12:57:52`,
        createdAt: Date.now(),
      },
    ], 'live')

    expect(candidates).toEqual([
      expect.objectContaining({
        sourceAlertId: 1003,
        matchTitle: '深圳新鹏城 vs 大连英博',
        marketTitle: '大小球 2/2.5',
        selectionName: '大球',
        referenceOdds: 0.82,
        targetOdds: 0.97,
        edge: 0.15,
        oddsChangeDirection: 'rise',
      }),
    ])
  })

  it('uses the first crown line from a live telegram alert record', () => {
    const signal = extractLatestCrownAlertSignal([
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
      selectionName: '米亚尔比',
      referenceSourceKey: 'crown',
      targetSourceKey: 'crown',
      referenceOdds: 0.73,
      targetOdds: 0.76,
      edge: 0.03,
      oddsChangeDirection: 'rise',
      bettingLogic: expect.stringContaining('telegram'),
    }))
  })

  it('lists every crown alert signal candidate with market and odds movement', () => {
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

    expect(candidates).toHaveLength(4)
    expect(candidates[0]).toEqual(expect.objectContaining({
      matchTitle: '米亚尔比 vs 哈马比',
      marketTitle: '让球 0/0.5',
      selectionName: '米亚尔比',
      referenceOdds: 0.73,
      targetOdds: 0.76,
      edge: 0.03,
      oddsChangeDirection: 'rise',
    }))
    expect(candidates[3]).toEqual(expect.objectContaining({
      matchTitle: '米亚尔比 vs 哈马比',
      marketTitle: '大小球 3',
      selectionName: '小球',
      referenceOdds: 0.93,
      targetOdds: 0.85,
      edge: 0.08,
      oddsChangeDirection: 'drop',
    }))
  })

  it('keeps only the newest telegram alert batch before applying betting settings', () => {
    const olderSignal = {
      ...prematchSignal,
      sourceAlertId: 10,
      sourceAlertCreatedAt: 1_000,
      matchTitle: 'Older match',
      targetOdds: 1.11,
      odds: 1.11,
    }
    const newestSignal = {
      ...prematchSignal,
      sourceAlertId: 11,
      sourceAlertCreatedAt: 2_000,
      matchTitle: 'Newest match',
      targetOdds: 0.88,
      odds: 0.88,
    }

    expect(filterLatestCrownAlertSignalBatch([newestSignal, olderSignal])).toEqual([newestSignal])
  })

  it('parses crown alert signal candidates from default telegram blocks with pinnacle before crown', () => {
    const candidates = extractCrownAlertSignalCandidates([
      {
        id: 1002,
        title: '赔率变动：曼城 vs 利物浦',
        message: `赛前赔率变动

联赛：英超
比赛：曼城 vs 利物浦
盘口：让球 主队 -0.5
平博：0.91 -> 0.95
皇冠：0.90 -> 0.94

盘口：让球 客队 -0.5
平博：0.92 -> 0.88
皇冠：1.10 -> 1.06

筛选：动水通过 / 合水通过
时间：2026-05-14 16:04:10`,
        createdAt: Date.now(),
      },
    ], 'prematch')

    expect(candidates).toEqual([
      expect.objectContaining({
        sourceAlertId: 1002,
        matchTitle: '曼城 vs 利物浦',
        marketTitle: '让球 -0.5',
        selectionName: '曼城',
        referenceSourceKey: 'crown',
        targetSourceKey: 'crown',
        referenceOdds: 0.90,
        targetOdds: 0.94,
        edge: 0.04,
        oddsChangeDirection: 'rise',
      }),
      expect.objectContaining({
        sourceAlertId: 1002,
        matchTitle: '曼城 vs 利物浦',
        marketTitle: '让球 -0.5',
        selectionName: '利物浦',
        referenceSourceKey: 'crown',
        targetSourceKey: 'crown',
        referenceOdds: 1.10,
        targetOdds: 1.06,
        edge: 0.04,
        oddsChangeDirection: 'drop',
      }),
    ])
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

  it('formats backend betting failure codes for operators', () => {
    expect(formatAutoBettingReason('crown_line_mismatch')).toBe('皇冠盘口已变化')
    expect(formatAutoBettingReason('stale_signal')).toBe('信号已过期')
    expect(formatAutoBettingReason('crown_execution_timeout')).toBe('皇冠执行确认超时')
    expect(formatAutoBettingReason('unknown_reason')).toBe('unknown_reason')
  })
})
