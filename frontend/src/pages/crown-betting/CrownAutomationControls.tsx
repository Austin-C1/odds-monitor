import type { CSSProperties } from 'react'
import { SaveOutlined } from '@ant-design/icons'
import { Button, Card, InputNumber, Segmented, Space, Switch, Tag, Typography } from 'antd'
import type {
  AutoBettingExecutionPlan,
  AutoBettingMode,
  AutoBettingSignal,
  QueuedCrownAlertSignal,
} from '../crownBettingExecutionPlan'
import { automationModeOptions } from '../crownBettingSettings'
import type { AutomationSettings, CrownAccount, CurrentExecutionStep } from '../crownBettingTypes'
import { CrownExecutionResults } from './CrownExecutionResults'
import { CrownSignalCandidates } from './CrownSignalCandidates'

const { Text } = Typography

const automationControlGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
  gap: 12,
  alignItems: 'end',
  marginBottom: 16,
}

const automationFieldStyle: CSSProperties = {
  minWidth: 0,
}

const automationSignalStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(220px, 1.3fr) repeat(4, minmax(120px, 1fr))',
  gap: 12,
  padding: '10px 0',
  borderTop: '1px solid #f0f0f0',
  borderBottom: '1px solid #f0f0f0',
  marginBottom: 12,
}

const executionStageStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
  gap: 12,
  padding: '10px 0',
  borderTop: '1px solid #f0f0f0',
  marginBottom: 12,
}

type CrownAutomationControlsProps = {
  settings: AutomationSettings
  updateAutomationSetting: <Key extends keyof AutomationSettings>(
    key: Key,
    value: AutomationSettings[Key],
  ) => void
  saveAutomationSettings: () => void
  executionRunning: boolean
  enabledBettingAccounts: CrownAccount[]
  currentExecutionSignal: AutoBettingSignal | null | undefined
  currentExecutionSignalQueueItem: QueuedCrownAlertSignal | null | undefined
  currentExecutionSignalLabel: string
  currentExecutionStep: CurrentExecutionStep
  signalCandidates: QueuedCrownAlertSignal[]
  executionPlan: AutoBettingExecutionPlan | null
  executionAccountTagColor: string
  executionAccountTagLabel: string
}

export const CrownAutomationControls = ({
  settings,
  updateAutomationSetting,
  saveAutomationSettings,
  executionRunning,
  enabledBettingAccounts,
  currentExecutionSignal,
  currentExecutionSignalQueueItem,
  currentExecutionSignalLabel,
  currentExecutionStep,
  signalCandidates,
  executionPlan,
  executionAccountTagColor,
  executionAccountTagLabel,
}: CrownAutomationControlsProps) => {
  const {
    autoMode,
    autoEnabled,
    perAccountLimit,
    betLimit,
    minimumBetOdds,
    signalMaxAgeSeconds,
  } = settings

  return (
    <Card
      title="自动化接入投注功能"
      size="small"
      style={{ marginTop: 16 }}
      extra={<Tag color={autoEnabled ? 'green' : 'default'}>{autoEnabled ? '自动投注' : '自动投注关闭'}</Tag>}
    >
      <div className="crown-automation-control-grid" style={automationControlGridStyle}>
        <div style={automationFieldStyle}>
          <Text strong>投注模式</Text>
          <div style={{ marginTop: 8 }}>
            <Segmented
              options={automationModeOptions}
              value={autoMode}
              onChange={(value) => updateAutomationSetting('autoMode', value as AutoBettingMode)}
            />
          </div>
        </div>
        <div style={automationFieldStyle}>
          <Text strong>自动投注开关</Text>
          <div style={{ marginTop: 8 }}>
            <Switch
              checked={autoEnabled}
              checkedChildren="开启"
              unCheckedChildren="关闭"
              onChange={(checked) => updateAutomationSetting('autoEnabled', checked)}
            />
          </div>
        </div>
        <div style={automationFieldStyle}>
          <Text strong>每号金额限制</Text>
          <InputNumber
            min={50}
            max={500}
            step={10}
            precision={0}
            value={perAccountLimit}
            onChange={(value) => updateAutomationSetting('perAccountLimit', Number(value || 0))}
            style={{ width: '100%', marginTop: 8 }}
          />
        </div>
        <div style={automationFieldStyle}>
          <Text strong>投注上限</Text>
          <InputNumber
            min={10}
            step={10}
            precision={0}
            value={betLimit}
            onChange={(value) => updateAutomationSetting('betLimit', Number(value || 0))}
            style={{ width: '100%', marginTop: 8 }}
          />
        </div>
        <div style={automationFieldStyle}>
          <Text strong>最低投注水位</Text>
          <InputNumber
            min={0.01}
            step={0.01}
            precision={2}
            value={minimumBetOdds}
            onChange={(value) => updateAutomationSetting('minimumBetOdds', Number(value || 0))}
            style={{ width: '100%', marginTop: 8 }}
          />
        </div>
        <div style={automationFieldStyle}>
          <Text strong>信号有效期(秒)</Text>
          <InputNumber
            min={1}
            max={3600}
            step={30}
            precision={0}
            value={signalMaxAgeSeconds}
            onChange={(value) => updateAutomationSetting('signalMaxAgeSeconds', Number(value || 0))}
            style={{ width: '100%', marginTop: 8 }}
          />
        </div>
        <div style={automationFieldStyle}>
          <Text strong>设置</Text>
          <Button
            icon={<SaveOutlined />}
            onClick={saveAutomationSettings}
            style={{ width: '100%', marginTop: 8 }}
          >
            保存设置
          </Button>
        </div>
      </div>

      <div style={{ marginBottom: 12 }}>
        <Space wrap>
          <Tag color={autoEnabled ? 'processing' : 'default'}>
            {autoEnabled ? '自动监听中' : '自动投注已关闭'}
          </Tag>
          {executionRunning ? <Tag color="blue">正在投注盘口</Tag> : null}
          <Tag color="blue">信号来源：采集系统合格回传</Tag>
          <Tag color={enabledBettingAccounts.length > 0 ? 'success' : 'error'}>
            启用账号 {enabledBettingAccounts.length} 个
          </Tag>
          <Tag color="default">异常账号自动跳过</Tag>
        </Space>
      </div>

      <div className="crown-automation-signal" style={automationSignalStyle}>
        <div>
          <Text type="secondary">{currentExecutionSignalLabel}</Text>
          <div>
            <Space size={6} wrap>
              <Text strong>{currentExecutionSignal?.matchTitle || '等待监控信号'}</Text>
              {currentExecutionSignalQueueItem ? (
                <Tag color={executionRunning ? 'processing' : 'default'}>队列 #{currentExecutionSignalQueueItem.queuePosition}</Tag>
              ) : null}
            </Space>
          </div>
        </div>
        <div>
          <Text type="secondary">模式</Text>
          <div>
            <Tag color={(currentExecutionSignal?.matchPhase || autoMode) === 'prematch' ? 'blue' : 'orange'}>
              {currentExecutionSignal?.modeLabel || (autoMode === 'prematch' ? '赛前' : '滚球')}
            </Tag>
          </div>
        </div>
        <div>
          <Text type="secondary">盘口</Text>
          <div>{currentExecutionSignal?.marketTitle || '-'}</div>
        </div>
        <div>
          <Text type="secondary">投注选项</Text>
          <div>{currentExecutionSignal?.selectionName || '-'}</div>
        </div>
        <div>
          <Text type="secondary">目标赔率</Text>
          <div>{currentExecutionSignal ? currentExecutionSignal.odds.toFixed(3) : '-'}</div>
        </div>
        <div>
          <Text type="secondary">投注逻辑</Text>
          <div>{currentExecutionSignal?.bettingLogic || '-'}</div>
        </div>
      </div>

      {currentExecutionStep ? (
        <div className="crown-execution-stage" style={executionStageStyle}>
          <div>
            <Text type="secondary">当前阶段</Text>
            <div><Tag color={executionRunning ? 'processing' : 'default'}>{currentExecutionStep.stageLabel}</Tag></div>
          </div>
          <div>
            <Text type="secondary">当前账号</Text>
            <div><Text strong>{currentExecutionStep.accountName}</Text></div>
          </div>
          <div>
            <Text type="secondary">当前模式</Text>
            <div>{currentExecutionStep.phaseLabel}</div>
          </div>
          <div>
            <Text type="secondary">当前队列</Text>
            <div>{currentExecutionStep.queueLabel}</div>
          </div>
          <div>
            <Text type="secondary">当前信号</Text>
            <div>{currentExecutionStep.signalTitle}</div>
          </div>
        </div>
      ) : null}

      <CrownSignalCandidates signalCandidates={signalCandidates} />
      <CrownExecutionResults
        executionPlan={executionPlan}
        executionAccountTagColor={executionAccountTagColor}
        executionAccountTagLabel={executionAccountTagLabel}
      />
    </Card>
  )
}
