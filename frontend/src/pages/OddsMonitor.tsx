import type { ReactNode } from 'react'
import { Empty, Spin, Typography } from 'antd'
import { MarketCollapsePanel } from './odds-monitor/MarketCollapsePanel'
import { MatchListPanel } from './odds-monitor/MatchListPanel'
import { OddsMonitorTopbar } from './odds-monitor/OddsMonitorTopbar'
import { formatDateTime, matchName, platformColors, platformLabels } from './odds-monitor/labels'
import { useOddsMonitorData } from './odds-monitor/useOddsMonitorData'
import './OddsMonitor.css'

const { Title, Text } = Typography

const renderMonitorFallback = (content: ReactNode, matchCount: number) => (
  <div className="odds-monitor-page light-mode">
    <OddsMonitorTopbar matchCount={matchCount} />
    <div className="odds-monitor-fallback">
      {content}
    </div>
  </div>
)

const OddsMonitor = () => {
  const {
    loading,
    matches,
    matchViews,
    filteredMatches,
    groupedMatches,
    statusCounts,
    matchSearchQuery,
    setMatchSearchQuery,
    matchFilter,
    setMatchFilter,
    activeMatch,
    activeMatchViewKey,
    setActiveMatchViewKey,
    localizedSelected,
    chartTimestamps,
    marketGroups,
    jumpToSearchMatch,
  } = useOddsMonitorData()

  if (loading || !localizedSelected || !activeMatch) {
    return renderMonitorFallback(
      loading ? <Spin /> : <Empty description="暂无比赛数据" />,
      matches.length,
    )
  }

  return (
    <div className="odds-monitor-page light-mode">
      <OddsMonitorTopbar matchCount={matches.length} />

      <div className="odds-monitor-workspace">
        <MatchListPanel
          groupedMatches={groupedMatches}
          filteredMatchCount={filteredMatches.length}
          totalMatchCount={matchViews.length}
          matchSearchQuery={matchSearchQuery}
          matchFilter={matchFilter}
          activeMatchViewKey={activeMatchViewKey || activeMatch.viewKey}
          onSearchQueryChange={setMatchSearchQuery}
          onSearchJump={jumpToSearchMatch}
          onFilterChange={setMatchFilter}
          onSelectMatch={setActiveMatchViewKey}
        />

        <main className="monitor-main">
          <section className="match-heading">
            <div>
              <Title level={2}>{matchName(localizedSelected.match)}</Title>
              <Text type="secondary">
                {localizedSelected.match.leagueName} · {formatDateTime(localizedSelected.match.startTime)} · {localizedSelected.match.status}
              </Text>
            </div>
            <div className="source-legend">
              {(localizedSelected.match.matchedPlatforms || []).map((platform) => (
                <span key={platform}>
                  <i style={{ background: platformColors[platform] }} />
                  {platformLabels[platform]}
                </span>
              ))}
            </div>
          </section>

          <section className="status-strip">
            <span>皇冠 {statusCounts.crown}</span>
            <span>单源比赛 {statusCounts.isolated}</span>
          </section>

          <MarketCollapsePanel marketGroups={marketGroups} chartTimestamps={chartTimestamps} />
        </main>
      </div>
    </div>
  )
}

export default OddsMonitor
