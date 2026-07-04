import type { CSSProperties } from 'react'
import { Empty, Space, Tag, Typography } from 'antd'
import type { AutoBettingExecutionPlan } from '../crownBettingExecutionPlan'
import { formatCurrency } from './formatters'

const { Text } = Typography

const executionResultGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(150px, 1.1fr) 96px 76px minmax(170px, 1.2fr) minmax(120px, 1fr) 90px 112px minmax(150px, 1fr)',
  gap: 10,
  alignItems: 'center',
}

const executionResultRowStyle: CSSProperties = {
  padding: '8px 0',
  borderBottom: '1px solid #f0f0f0',
}

type CrownExecutionResultsProps = {
  executionPlan: AutoBettingExecutionPlan | null
  executionAccountTagColor: string
  executionAccountTagLabel: string
}

export const CrownExecutionResults = ({
  executionPlan,
  executionAccountTagColor,
  executionAccountTagLabel,
}: CrownExecutionResultsProps) => (
  <>
    <div style={{ marginBottom: 8 }}>
      <Space wrap>
        <Text strong>自动投注结果</Text>
        {executionPlan ? (
          <>
            <Tag color="processing">{executionPlan.modeLabel}</Tag>
            <Tag color="blue">总金额 {formatCurrency(executionPlan.totalStake)}</Tag>
            <Tag color={executionAccountTagColor}>{executionAccountTagLabel}</Tag>
            <Text type="secondary">{executionPlan.summary}</Text>
          </>
        ) : null}
      </Space>
    </div>

    {executionPlan ? (
      <div>
        <div style={{ ...executionResultGridStyle, paddingBottom: 8, borderBottom: '1px solid #f0f0f0' }}>
          <Text strong>账号</Text>
          <Text strong>结果</Text>
          <Text strong>模式</Text>
          <Text strong>比赛</Text>
          <Text strong>盘口</Text>
          <Text strong>赔率</Text>
          <Text strong>金额</Text>
          <Text strong>原因</Text>
        </div>
        {executionPlan.rows.length > 0 ? executionPlan.rows.map((row) => (
          <div key={row.id} style={{ ...executionResultGridStyle, ...executionResultRowStyle }}>
            <Text strong={row.status === 'passed'}>{row.accountName}</Text>
            <Tag color={row.status === 'passed' ? 'success' : 'default'}>{row.statusLabel}</Tag>
            <Tag color={row.matchPhase === 'prematch' ? 'blue' : 'orange'}>{row.modeLabel}</Tag>
            <Text>{row.matchTitle}</Text>
            <Space direction="vertical" size={0}>
              <Text>{row.marketTitle}</Text>
              <Text type="secondary">{row.selectionName}</Text>
              <Text type="secondary">{row.bettingLogic}</Text>
            </Space>
            <Text>{row.odds.toFixed(3)}</Text>
            <Text strong={row.stakeAmount > 0}>{formatCurrency(row.stakeAmount)}</Text>
            <Text type={row.status === 'passed' ? 'secondary' : 'warning'}>{row.reason}</Text>
          </div>
        )) : (
          <Empty description={executionPlan.summary} />
        )}
      </div>
    ) : (
      <Empty description="等待自动投注信号" />
    )}
  </>
)
