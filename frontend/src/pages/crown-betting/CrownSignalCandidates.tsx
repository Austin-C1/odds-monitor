import type { CSSProperties } from 'react'
import { Empty, Space, Tag, Typography } from 'antd'
import type { QueuedCrownAlertSignal } from '../crownBettingExecutionPlan'

const { Text } = Typography

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

type CrownSignalCandidatesProps = {
  signalCandidates: QueuedCrownAlertSignal[]
}

export const CrownSignalCandidates = ({ signalCandidates }: CrownSignalCandidatesProps) => (
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
)
