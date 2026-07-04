import { Button, Empty, Input, List, Typography } from 'antd'
import type { OddsMonitorPlatformMatchView } from '../OddsMonitorPlatformView'
import { formatDateTime, matchFilterLabels, matchName, platformColors, platformLabels } from './labels'
import type { MatchFilterKey } from './types'

const { Text } = Typography

export type OddsMonitorMatchGroup = {
  leagueName: string
  matches: OddsMonitorPlatformMatchView[]
}

type MatchListPanelProps = {
  groupedMatches: OddsMonitorMatchGroup[]
  filteredMatchCount: number
  totalMatchCount: number
  matchSearchQuery: string
  matchFilter: MatchFilterKey
  activeMatchViewKey: string | null | undefined
  onSearchQueryChange: (value: string) => void
  onSearchJump: (value: string) => void
  onFilterChange: (value: MatchFilterKey) => void
  onSelectMatch: (viewKey: string) => void
}

export const MatchListPanel = ({
  groupedMatches,
  filteredMatchCount,
  totalMatchCount,
  matchSearchQuery,
  matchFilter,
  activeMatchViewKey,
  onSearchQueryChange,
  onSearchJump,
  onFilterChange,
  onSelectMatch,
}: MatchListPanelProps) => {
  const renderMatchItem = (item: OddsMonitorPlatformMatchView) => (
    <List.Item
      className={item.viewKey === activeMatchViewKey ? 'match-item active' : 'match-item'}
      onClick={() => onSelectMatch(item.viewKey)}
    >
      <div className="match-card-content">
        <div className="match-title-row">
          <span className={item.alertCount > 0 ? 'change-dot has-change' : 'change-dot'} />
          <Text strong className="match-title">{matchName(item)}</Text>
        </div>
        <Text type="secondary" className="match-meta">{formatDateTime(item.startTime)}</Text>
        <div className="platform-row">
          {item.matchedPlatforms?.map((platform) => (
            <span
              key={platform}
              className="platform-pill"
              style={{ borderColor: platformColors[platform], color: platformColors[platform] }}
            >
              {platformLabels[platform]}
            </span>
          ))}
        </div>
      </div>
    </List.Item>
  )

  return (
    <aside className="match-panel">
      <div className="match-panel-header">
        <Text strong>比赛列表</Text>
        <Text type="secondary">
          {matchSearchQuery.trim()
            ? `${filteredMatchCount} / ${totalMatchCount} 项`
            : `${totalMatchCount} 项`}
        </Text>
      </div>
      <div className="match-search-bar">
        <Input.Search
          allowClear
          value={matchSearchQuery}
          placeholder="搜索比赛、联赛或平台"
          enterButton="跳转"
          onChange={(event) => onSearchQueryChange(event.target.value)}
          onSearch={onSearchJump}
        />
        <div className="match-filter-row">
          {(Object.keys(matchFilterLabels) as MatchFilterKey[]).map((filterKey) => (
            <Button
              key={filterKey}
              size="small"
              type={matchFilter === filterKey ? 'primary' : 'default'}
              onClick={() => onFilterChange(filterKey)}
            >
              {matchFilterLabels[filterKey]}
            </Button>
          ))}
        </div>
      </div>
      {filteredMatchCount > 0 ? (
        groupedMatches.map((group) => (
          <div className="league-group" key={group.leagueName}>
            <div className="league-heading">
              <Text strong>{group.leagueName}</Text>
              <Text type="secondary">{group.matches.length} 场</Text>
            </div>
            <List dataSource={group.matches} renderItem={renderMatchItem} />
          </div>
        ))
      ) : (
        <Empty className="match-search-empty" description="没有匹配比赛" />
      )}
    </aside>
  )
}
