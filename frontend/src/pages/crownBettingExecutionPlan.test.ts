import { describe, expect, it } from 'vitest'
import {
  autoBettingSignalKey,
  buildAutoBettingExecutionPlan,
  buildCrownAlertSignalQueue,
  executionOddsFloor,
  extractCrownAlertSignalCandidates,
  filterFreshCrownAlertSignals,
  formatAutoBettingReason,
  selectNextCrownAlertSignal,
  shouldCompleteCrownSignalForAccounts,
  type AutoBettingExecutionPlanRow,
  type AutoBettingSignal,
} from './crownBettingExecutionPlan'

const zh = {
  prematch: '\u8d5b\u524d',
  live: '\u6eda\u7403',
  league: '\u8054\u8d5b',
  match: '\u6bd4\u8d5b',
  market: '\u76d8\u53e3',
  crown: '\u7687\u51a0',
  filter: '\u7b5b\u9009',
  time: '\u65f6\u95f4',
  handicap: '\u8ba9\u7403',
  total: '\u5927\u5c0f\u7403',
  home: '\u4e3b\u961f',
  away: '\u5ba2\u961f',
  over: '\u5927\u7403',
  under: '\u5c0f\u7403',
  manCity: '\u66fc\u57ce',
  liverpool: '\u5229\u7269\u6d66',
  epl: '\u82f1\u8d85',
  swedenCup: '\u745e\u5178\u676f',
  mjaellby: '\u7c73\u4e9a\u5c14\u6bd4',
  hammarby: '\u54c8\u9a6c\u6bd4',
  shenzhen: '\u6df1\u5733\u65b0\u9e4f\u57ce',
  dalian: '\u5927\u8fde\u82f1\u535a',
  chinaSuperLeague: '\u4e2d\u56fd\u8d85\u7ea7\u8054\u8d5b',
}

const prematchSignal: AutoBettingSignal = {
  modeLabel: zh.prematch,
  bettingMode: 'prematch',
  matchPhase: 'prematch',
  leagueName: zh.epl,
  matchTitle: `${zh.manCity} vs ${zh.liverpool}`,
  marketType: 'handicap',
  marketTitle: `${zh.handicap} -0.5`,
  lineValue: '-0.5',
  selectionName: zh.manCity,
  referenceSourceKey: 'crown',
  targetSourceKey: 'crown',
  referenceOdds: 0.90,
  targetOdds: 0.94,
  odds: 0.94,
  edge: 0.04,
  oddsChangeDirection: 'rise',
  bettingLogic: `telegram: ${zh.manCity} 0.9 -> 0.94`,
}

describe('crown betting execution plan', () => {
  it('creates a usable signal from a single crown line in a telegram alert', () => {
    const candidates = extractCrownAlertSignalCandidates([
      {
        id: 1003,
        title: `\u8d54\u7387\u53d8\u52a8\uff1a${zh.shenzhen} vs ${zh.dalian}`,
        message: `${zh.live}\u8d54\u7387\u53d8\u52a8

${zh.league}\uff1a${zh.chinaSuperLeague}
${zh.match}\uff1a${zh.shenzhen} vs ${zh.dalian}
\u8fdb\u884c\uff1a\u7b2c 1 \u5206\u949f
\u6bd4\u5206\uff1a0-0

${zh.market}\uff1a${zh.total} ${zh.over} 2/2.5
${zh.crown}\uff1a0.82 -> 0.97

${zh.filter}\uff1a\u52a8\u6c34\u901a\u8fc7 / \u5408\u6c34\u901a\u8fc7
${zh.time}\uff1a2026-05-19 12:57:52`,
        createdAt: Date.now(),
      },
    ], 'live')

    expect(candidates).toEqual([
      expect.objectContaining({
        sourceAlertId: 1003,
        matchTitle: `${zh.shenzhen} vs ${zh.dalian}`,
        marketTitle: `${zh.total} 2/2.5`,
        selectionName: zh.over,
        referenceOdds: 0.82,
        targetOdds: 0.97,
        edge: 0.15,
        oddsChangeDirection: 'rise',
      }),
    ])
  })

  it('lists every crown alert signal candidate with market and odds movement', () => {
    const candidates = extractCrownAlertSignalCandidates([
      {
        id: 1001,
        title: `\u8d54\u7387\u53d8\u52a8\uff1a${zh.mjaellby} vs ${zh.hammarby}`,
        message: `${zh.live}\u8d54\u7387\u53d8\u52a8

${zh.league}\uff1a${zh.swedenCup}
${zh.match}\uff1a${zh.mjaellby} vs ${zh.hammarby}
\u8fdb\u884c\uff1a\u7b2c 2 \u5206\u949f
\u6bd4\u5206\uff1a0-1

${zh.market}\uff1a${zh.handicap} ${zh.home} 0/0.5
${zh.crown}\uff1a0.73 -> 0.76

${zh.market}\uff1a${zh.handicap} ${zh.away} 0/0.5
${zh.crown}\uff1a1.15 -> 1.12

${zh.market}\uff1a${zh.total} ${zh.over} 3
${zh.crown}\uff1a0.77 -> 0.85

${zh.market}\uff1a${zh.total} ${zh.under} 3
${zh.crown}\uff1a0.93 -> 0.85

${zh.filter}\uff1a\u52a8\u6c34\u901a\u8fc7 / \u5408\u6c34\u901a\u8fc7
${zh.time}\uff1a2026-05-14 16:04:10`,
        createdAt: Date.now(),
      },
    ], 'live')

    expect(candidates).toHaveLength(4)
    expect(candidates[0]).toEqual(expect.objectContaining({
      matchTitle: `${zh.mjaellby} vs ${zh.hammarby}`,
      marketTitle: `${zh.handicap} 0/0.5`,
      selectionName: zh.mjaellby,
      referenceOdds: 0.73,
      targetOdds: 0.76,
      edge: 0.03,
      oddsChangeDirection: 'rise',
    }))
    expect(candidates[3]).toEqual(expect.objectContaining({
      matchTitle: `${zh.mjaellby} vs ${zh.hammarby}`,
      marketTitle: `${zh.total} 3`,
      selectionName: zh.under,
      referenceOdds: 0.93,
      targetOdds: 0.85,
      edge: 0.08,
      oddsChangeDirection: 'drop',
    }))
  })

  it('keeps separate keys for different markets inside the same alert', () => {
    const handicapSignal = {
      ...prematchSignal,
      sourceAlertId: 3001,
      marketType: 'handicap' as const,
      lineValue: '-0.5',
      selectionName: zh.manCity,
      targetOdds: 0.94,
    }
    const totalSignal = {
      ...prematchSignal,
      sourceAlertId: 3001,
      marketType: 'total' as const,
      lineValue: '2.5',
      selectionName: zh.over,
      targetOdds: 0.97,
    }

    expect(autoBettingSignalKey(handicapSignal)).not.toBe(autoBettingSignalKey(totalSignal))
  })

  it('selects the next unhandled signal instead of getting stuck on the first candidate', () => {
    const firstSignal = {
      ...prematchSignal,
      sourceAlertId: 4001,
      matchTitle: 'First match',
      targetOdds: 0.94,
    }
    const secondSignal = {
      ...prematchSignal,
      sourceAlertId: 4002,
      matchTitle: 'Second match',
      targetOdds: 0.96,
    }
    const attemptedSignalAt = new Map([[autoBettingSignalKey(firstSignal), 1_000]])

    expect(selectNextCrownAlertSignal(
      [firstSignal, secondSignal],
      {
        completedSignalKeys: new Set(),
        attemptedSignalAt,
        now: 20_000,
        retryCooldownMs: 5_000,
      },
    )).toBe(secondSignal)
  })

  it('skips completed signals and keeps scanning later candidates', () => {
    const completedSignal = {
      ...prematchSignal,
      sourceAlertId: 4101,
      matchTitle: 'Completed match',
      targetOdds: 0.94,
    }
    const pendingSignal = {
      ...prematchSignal,
      sourceAlertId: 4102,
      matchTitle: 'Pending match',
      targetOdds: 0.96,
    }

    expect(selectNextCrownAlertSignal(
      [completedSignal, pendingSignal],
      {
        completedSignalKeys: new Set([autoBettingSignalKey(completedSignal)]),
        attemptedSignalAt: new Map(),
        now: 20_000,
        retryCooldownMs: 5_000,
      },
    )).toBe(pendingSignal)
  })

  it('numbers pending crown signals in the same order used for betting', () => {
    const completedSignal = {
      ...prematchSignal,
      sourceAlertId: 4201,
      matchTitle: 'Completed match',
      targetOdds: 0.94,
    }
    const firstPendingSignal = {
      ...prematchSignal,
      sourceAlertId: 4202,
      matchTitle: 'First pending match',
      targetOdds: 0.96,
    }
    const retrySignal = {
      ...prematchSignal,
      sourceAlertId: 4203,
      matchTitle: 'Retry match',
      targetOdds: 0.98,
    }

    const queue = buildCrownAlertSignalQueue(
      [completedSignal, retrySignal, firstPendingSignal],
      {
        completedSignalKeys: new Set([autoBettingSignalKey(completedSignal)]),
        attemptedSignalAt: new Map([[autoBettingSignalKey(retrySignal), 10_000]]),
        now: 20_000,
        retryCooldownMs: 5_000,
      },
    )

    expect(queue.map((signal) => ({
      queuePosition: signal.queuePosition,
      matchTitle: signal.matchTitle,
      queueStatus: signal.queueStatus,
    }))).toEqual([
      { queuePosition: 1, matchTitle: 'First pending match', queueStatus: 'ready' },
      { queuePosition: 2, matchTitle: 'Retry match', queueStatus: 'ready' },
    ])
    expect(selectNextCrownAlertSignal(
      [completedSignal, retrySignal, firstPendingSignal],
      {
        completedSignalKeys: new Set([autoBettingSignalKey(completedSignal)]),
        attemptedSignalAt: new Map([[autoBettingSignalKey(retrySignal), 10_000]]),
        now: 20_000,
        retryCooldownMs: 5_000,
      },
    )).toBe(firstPendingSignal)
  })

  it('drops crown alert candidates older than the configured signal age', () => {
    const freshSignal = {
      ...prematchSignal,
      sourceAlertId: 21,
      sourceAlertCreatedAt: 1_000_000,
    }
    const expiredSignal = {
      ...prematchSignal,
      sourceAlertId: 20,
      sourceAlertCreatedAt: 399_999,
    }

    expect(filterFreshCrownAlertSignals(
      [freshSignal, expiredSignal],
      1_000_000,
      360,
    )).toEqual([freshSignal])
  })

  it('parses crown alert signal candidates from default telegram blocks with pinnacle before crown', () => {
    const candidates = extractCrownAlertSignalCandidates([
      {
        id: 1002,
        title: `\u8d54\u7387\u53d8\u52a8\uff1a${zh.manCity} vs ${zh.liverpool}`,
        message: `${zh.prematch}\u8d54\u7387\u53d8\u52a8

${zh.league}\uff1a${zh.epl}
${zh.match}\uff1a${zh.manCity} vs ${zh.liverpool}
${zh.market}\uff1a${zh.handicap} ${zh.home} -0.5
\u5e73\u535a\uff1a0.91 -> 0.95
${zh.crown}\uff1a0.90 -> 0.94

${zh.market}\uff1a${zh.handicap} ${zh.away} -0.5
\u5e73\u535a\uff1a0.92 -> 0.88
${zh.crown}\uff1a1.10 -> 1.06

${zh.filter}\uff1a\u52a8\u6c34\u901a\u8fc7 / \u5408\u6c34\u901a\u8fc7
${zh.time}\uff1a2026-05-14 16:04:10`,
        createdAt: Date.now(),
      },
    ], 'prematch')

    expect(candidates).toEqual([
      expect.objectContaining({
        sourceAlertId: 1002,
        matchTitle: `${zh.manCity} vs ${zh.liverpool}`,
        marketTitle: `${zh.handicap} -0.5`,
        selectionName: zh.manCity,
        referenceSourceKey: 'crown',
        targetSourceKey: 'crown',
        referenceOdds: 0.90,
        targetOdds: 0.94,
        edge: 0.04,
        oddsChangeDirection: 'rise',
      }),
      expect.objectContaining({
        sourceAlertId: 1002,
        matchTitle: `${zh.manCity} vs ${zh.liverpool}`,
        marketTitle: `${zh.handicap} -0.5`,
        selectionName: zh.liverpool,
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
      minimumBetOdds: 0.7,
      accounts: [
        { id: 'a', displayName: 'main account', status: 'success', adsPowerProfileId: 'profile-a', adsPowerStatus: 'opened' },
        { id: 'b', displayName: 'sub account', status: 'success', adsPowerProfileId: 'profile-b', adsPowerStatus: 'opened' },
        { id: 'c', displayName: 'bad account', status: 'error' },
      ],
    })

    expect(result.canExecute).toBe(true)
    expect(result.modeLabel).toBe(zh.prematch)
    expect(result.totalStake).toBe(500)
    expect(result.availableAccountCount).toBe(2)
    expect(result.rows).toEqual([
      expect.objectContaining({ accountName: 'main account', status: 'passed', matchTitle: `${zh.manCity} vs ${zh.liverpool}`, stakeAmount: 250 }),
      expect.objectContaining({ accountName: 'sub account', status: 'passed', matchTitle: `${zh.manCity} vs ${zh.liverpool}`, stakeAmount: 250 }),
      expect.objectContaining({ accountName: 'bad account', status: 'skipped', stakeAmount: 0 }),
    ])
  })

  it('does not mark an opened but offline AdsPower profile as unopened', () => {
    const result = buildAutoBettingExecutionPlan({
      signal: prematchSignal,
      mode: 'prematch',
      enabled: true,
      perAccountLimit: 300,
      betLimit: 500,
      minimumBetOdds: 0.7,
      accounts: [
        { id: 'a', displayName: 'opened offline account', status: 'error', adsPowerProfileId: 'profile-a', adsPowerStatus: 'opened' },
      ],
    })

    expect(result.canExecute).toBe(false)
    expect(result.rows).toEqual([
      expect.objectContaining({ status: 'skipped', stakeAmount: 0 }),
    ])
  })

  it('shows AdsPower errors separately from unopened environments', () => {
    const result = buildAutoBettingExecutionPlan({
      signal: prematchSignal,
      mode: 'prematch',
      enabled: true,
      perAccountLimit: 300,
      betLimit: 500,
      minimumBetOdds: 0.7,
      accounts: [
        { id: 'a', displayName: 'error account', status: 'error', adsPowerProfileId: 'profile-a', adsPowerStatus: 'error' },
      ],
    })

    expect(result.canExecute).toBe(false)
    expect(result.rows).toEqual([
      expect.objectContaining({ status: 'skipped', stakeAmount: 0 }),
    ])
  })

  it('locks execution to the selected betting mode', () => {
    const result = buildAutoBettingExecutionPlan({
      signal: { ...prematchSignal, modeLabel: zh.live, bettingMode: 'live', matchPhase: 'live' },
      mode: 'prematch',
      enabled: true,
      perAccountLimit: 300,
      betLimit: 500,
      minimumBetOdds: 0.7,
      accounts: [
        { id: 'a', displayName: 'main account', status: 'success', adsPowerProfileId: 'profile-a', adsPowerStatus: 'opened' },
      ],
    })

    expect(result.canExecute).toBe(false)
    expect(result.rows).toEqual([])
  })

  it('blocks execution until the monitor has a usable crown signal', () => {
    const result = buildAutoBettingExecutionPlan({
      signal: null,
      mode: 'prematch',
      enabled: true,
      perAccountLimit: 300,
      betLimit: 500,
      minimumBetOdds: 0.7,
      accounts: [
        { id: 'a', displayName: 'main account', status: 'success', adsPowerProfileId: 'profile-a', adsPowerStatus: 'opened' },
      ],
    })

    expect(result.canExecute).toBe(false)
    expect(result.rows).toEqual([])
  })

  it('skips execution when the target crown water is below the configured floor', () => {
    const result = buildAutoBettingExecutionPlan({
      signal: { ...prematchSignal, targetOdds: 0.62, odds: 0.62 },
      mode: 'prematch',
      enabled: true,
      perAccountLimit: 300,
      betLimit: 500,
      minimumBetOdds: 0.7,
      accounts: [
        { id: 'a', displayName: 'main account', status: 'success', adsPowerProfileId: 'profile-a', adsPowerStatus: 'opened' },
      ],
    })

    expect(result.canExecute).toBe(false)
    expect(result.rows).toEqual([
      expect.objectContaining({ status: 'skipped', stakeAmount: 0 }),
    ])
  })

  it('allows execution by target water even when edge is below the configured edge value', () => {
    const result = buildAutoBettingExecutionPlan({
      signal: { ...prematchSignal, referenceOdds: 1.07, targetOdds: 1.08, odds: 1.08, edge: 0.01 },
      mode: 'prematch',
      enabled: true,
      perAccountLimit: 300,
      betLimit: 500,
      minimumBetOdds: 1.01,
      accounts: [
        { id: 'a', displayName: 'main account', status: 'success', adsPowerProfileId: 'profile-a', adsPowerStatus: 'opened' },
      ],
    })

    expect(result.canExecute).toBe(true)
    expect(result.rows).toEqual([
      expect.objectContaining({ status: 'passed', stakeAmount: 300 }),
    ])
  })

  it('uses the higher value between signal water and configured minimum for crown execution', () => {
    expect(executionOddsFloor(1.08, 1.01)).toBe(1.08)
    expect(executionOddsFloor(0.98, 1.01)).toBe(1.01)
    expect(executionOddsFloor(1.12345, 1.01)).toBe(1.1235)
  })

  it('formats backend betting failure codes for operators', () => {
    expect(formatAutoBettingReason('unknown_reason')).toBe('unknown_reason')
    expect(formatAutoBettingReason('')).toBe('')
    expect(formatAutoBettingReason('crown_network_unstable')).toBe('皇冠网络不稳定，请刷新后重试')
    expect(formatAutoBettingReason('crown_page_activation_failed')).toBe('皇冠页面刷新确认失败')
    expect(formatAutoBettingReason('crown_phase_unknown')).toBe('皇冠比赛阶段无法确认')
    expect(formatAutoBettingReason('crown_phase_mismatch')).toBe('皇冠比赛阶段与信号不一致')
    expect(formatAutoBettingReason('crown_stake_input_not_applied')).toBe('皇冠金额未成功输入')
    expect(formatAutoBettingReason('crown_betslip_stake_input_not_applied')).toBe('皇冠注单金额未成功输入')
    expect(formatAutoBettingReason('duplicate_placed_intent')).toBe('已成功投注，重复信号已跳过')
  })

  it('does not keep removed auto betting restriction reason labels', () => {
    expect(formatAutoBettingReason('crown_odds_moved')).toBe('crown_odds_moved')
  })

  it('keeps a signal retryable until every betting account is complete', () => {
    const row = (id: string, overrides: Partial<AutoBettingExecutionPlanRow>): AutoBettingExecutionPlanRow => ({
      id,
      accountName: id,
      status: 'skipped',
      statusLabel: '跳过',
      modeLabel: zh.live,
      bettingMode: 'live',
      matchPhase: 'live',
      leagueName: zh.league,
      matchTitle: 'Lazio v Pisa',
      marketType: 'handicap',
      marketTitle: zh.handicap,
      lineValue: '-0.5',
      selectionName: 'Lazio',
      referenceSourceKey: 'crown',
      targetSourceKey: 'crown',
      referenceOdds: 1.08,
      targetOdds: 1.17,
      odds: 1.17,
      edge: 0.09,
      bettingLogic: 'test',
      capturedAt: Date.now(),
      stakeAmount: 0,
      reason: '账号投注超过30秒，已进入下一个账号',
      ...overrides,
    })

    expect(shouldCompleteCrownSignalForAccounts([
      row('account-a', { status: 'passed', statusLabel: '已下注', stakeAmount: 50, reason: '已确认下注' }),
      row('account-b', {}),
    ], ['account-a', 'account-b'])).toBe(false)

    expect(shouldCompleteCrownSignalForAccounts([
      row('account-a', { retryable: false, reason: '已成功投注，重复信号已跳过' }),
      row('account-b', { status: 'passed', statusLabel: '已下注', stakeAmount: 50, reason: '已确认下注' }),
    ], ['account-a', 'account-b'])).toBe(true)
  })
})
