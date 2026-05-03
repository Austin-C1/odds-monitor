import { describe, expect, it } from 'vitest'
import { createPlatformMatchViews, selectPlatformDetail } from './OddsMonitorPlatformView'

describe('odds monitor platform views', () => {
  it('keeps one matched game as one match entry', () => {
    const views = createPlatformMatchViews([
      {
        id: 10,
        leagueName: '日本 - J联赛',
        homeTeam: '冈山绿雉',
        awayTeam: '广岛三箭',
        startTime: 1777700000000,
        status: 'scheduled',
        sourceCount: 3,
        alertCount: 0,
        matchedPlatforms: ['pinnacle', 'crown', 'polymarket'],
      },
    ])

    expect(views.map((view) => view.viewKey)).toEqual(['10'])
    expect(views[0].matchedPlatforms).toEqual(['pinnacle', 'crown', 'polymarket'])
  })

  it('keeps all platform odds in the detail view', () => {
    const [matchView] = createPlatformMatchViews([
      {
        id: 11,
        leagueName: '日本 - J联赛',
        homeTeam: '冈山绿雉',
        awayTeam: '广岛三箭',
        startTime: 1777700000000,
        status: 'scheduled',
        sourceCount: 2,
        alertCount: 0,
        matchedPlatforms: ['pinnacle', 'crown'],
      },
    ])

    const detail = {
      match: matchView.sourceMatch,
      metrics: [
        { label: 'handicap home 0.5', value: '1.060', trend: '', sourceKey: 'pinnacle' },
        { label: 'handicap home 0.5', value: '0.950', trend: '', sourceKey: 'crown' },
      ],
      oddsHistory: [],
    }

    expect(selectPlatformDetail(detail, matchView).metrics).toEqual([
      { label: 'handicap home 0.5', value: '1.060', trend: '', sourceKey: 'pinnacle' },
      { label: 'handicap home 0.5', value: '0.950', trend: '', sourceKey: 'crown' },
    ])
  })
})
