import { describe, expect, it } from 'vitest'
import { filterMatchesBySearchQuery, getSearchJumpMatch, shouldAutoJumpForSearchQuery } from './OddsMonitorSearch'

describe('odds monitor match search', () => {
  const matches = [
    {
      id: 1,
      leagueName: '法国乙组联赛',
      homeTeam: '亚眠',
      awayTeam: '红星',
      startTime: 1777752000000,
      status: '赛前',
      sourceCount: 2,
      alertCount: 0,
      matchedPlatforms: ['pinnacle', 'crown'],
    },
    {
      id: 2,
      leagueName: '美国足球冠军联赛',
      homeTeam: '劳顿联',
      awayTeam: '奥克兰根',
      startTime: 1777741200000,
      status: '赛前',
      sourceCount: 1,
      alertCount: 0,
      matchedPlatforms: ['crown'],
    },
  ] as const

  it('filters matches by team, league, and platform label', () => {
    expect(filterMatchesBySearchQuery(matches, '亚眠').map((item) => item.id)).toEqual([1])
    expect(filterMatchesBySearchQuery(matches, '冠军联赛').map((item) => item.id)).toEqual([2])
    expect(filterMatchesBySearchQuery(matches, '皇冠').map((item) => item.id)).toEqual([1, 2])
  })

  it('uses the first filtered match as the immediate jump target', () => {
    expect(getSearchJumpMatch(matches, '奥克兰')?.id).toBe(2)
    expect(getSearchJumpMatch(matches, '不存在')).toBeUndefined()
  })

  it('only auto jumps once for the same search query', () => {
    expect(shouldAutoJumpForSearchQuery('', '日本')).toBe(true)
    expect(shouldAutoJumpForSearchQuery('日本', '日本')).toBe(false)
    expect(shouldAutoJumpForSearchQuery('日本', ' 日本 ')).toBe(false)
    expect(shouldAutoJumpForSearchQuery('日本', '日本J')).toBe(true)
    expect(shouldAutoJumpForSearchQuery('日本', '')).toBe(false)
  })
})
