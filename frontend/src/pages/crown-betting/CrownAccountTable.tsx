import type { CSSProperties } from 'react'
import { ApiOutlined } from '@ant-design/icons'
import { Button, Card, Empty, Popconfirm, Space, Statistic, Switch, Tag, Typography } from 'antd'
import { hasAdsPowerProfile } from '../crownBettingAccounts'
import type {
  AdsPowerStatusResponse,
  CrownAccount,
  CrownAccountStatus,
} from '../crownBettingTypes'
import { formatCurrency, formatDateTime } from './formatters'

const { Text } = Typography

const accountGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'minmax(220px, 1.35fr) 136px 112px minmax(170px, 1fr) minmax(180px, 1fr) 184px',
  gap: 12,
  alignItems: 'center',
}

const accountRowStyle: CSSProperties = {
  padding: '8px 0',
  borderBottom: '1px solid #f0f0f0',
}

const compactAccountCardStyles = {
  header: { minHeight: 44, padding: '0 16px' },
  body: { padding: '10px 16px 12px' },
}

const valueBlockStyle: CSSProperties = {
  minWidth: 0,
}

const statusMeta: Record<CrownAccountStatus, { label: string; color: string }> = {
  unchecked: { label: '待检测', color: 'default' },
  checking: { label: '检测中', color: 'processing' },
  success: { label: '在线', color: 'success' },
  error: { label: '异常', color: 'error' },
}

const formatBalanceState = (account: CrownAccount) => {
  if (typeof account.balance === 'number') {
    return formatCurrency(account.balance, account.currency)
  }
  return account.status === 'success' ? '余额未返回' : '待检测'
}

const accountCheckSummary = (account: CrownAccount) => {
  if (account.status === 'checking') return '正在检测 AdsPower 环境状态'
  if (account.note) return account.note
  if (account.status === 'success') return typeof account.balance === 'number' ? '账号在线，余额已获取' : '账号在线，余额未读取到'
  if (account.status === 'error') return '检测失败'
  return '等待检测'
}

const adsPowerStatusLabel = (account: CrownAccount) => {
  if (!hasAdsPowerProfile(account)) return '未绑定 AdsPower 档案'
  if (account.adsPowerStatus === 'starting') return '正在打开 AdsPower 环境'
  if (account.adsPowerStatus === 'opened') return 'AdsPower 环境已打开'
  if (account.adsPowerStatus === 'closed') return 'AdsPower 环境未打开'
  if (account.adsPowerStatus === 'error') return account.adsPowerMessage || 'AdsPower 环境异常'
  return '已绑定 AdsPower 档案'
}

const adsPowerStatusColor = (account: CrownAccount) => {
  if (!hasAdsPowerProfile(account)) return 'default'
  if (account.adsPowerStatus === 'opened') return 'success'
  if (account.adsPowerStatus === 'starting') return 'processing'
  if (account.adsPowerStatus === 'closed') return 'warning'
  if (account.adsPowerStatus === 'error') return 'error'
  return 'blue'
}

type CrownAccountTableProps = {
  accounts: CrownAccount[]
  updateAccount: (id: string, patch: Partial<CrownAccount>) => void
  checkAccount: (account: CrownAccount) => void
  openAdsPowerProfile: (account: CrownAccount) => void
  deleteAccount: (id: string) => void
}

export const CrownAccountSummary = ({
  accounts,
  totalBalance,
  abnormalCount,
  boundProfileCount,
  adsPowerStatus,
}: {
  accounts: CrownAccount[]
  totalBalance: number
  abnormalCount: number
  boundProfileCount: number
  adsPowerStatus: AdsPowerStatusResponse | null
}) => (
  <div className="page-stat-grid">
    <Card size="small">
      <Statistic title="账号数量" value={accounts.length} suffix="个" />
    </Card>
    <Card size="small">
      <Statistic title="账号余额" value={totalBalance} precision={2} prefix="¥" />
    </Card>
    <Card size="small">
      <Statistic title="异常账号" value={abnormalCount} suffix="个" valueStyle={{ color: abnormalCount ? '#cf1322' : '#3f8600' }} />
    </Card>
    <Card size="small">
      <Statistic title="AdsPower 环境" value={boundProfileCount} suffix="个" />
      {adsPowerStatus ? (
        <Tag color={adsPowerStatus.available ? 'success' : 'error'} style={{ marginTop: 6 }}>
          {adsPowerStatus.available ? 'AdsPower 已连接' : 'AdsPower 未连接'}
        </Tag>
      ) : null}
    </Card>
  </div>
)

export const CrownAccountTable = ({
  accounts,
  updateAccount,
  checkAccount,
  openAdsPowerProfile,
  deleteAccount,
}: CrownAccountTableProps) => (
  <Card
    size="small"
    title="账号与 AdsPower 环境"
    extra={(
      <Space wrap>
        <Tag color="blue">每个账号绑定一个独立 AdsPower 环境</Tag>
        <Tag color="default">监控账号不进入投注区</Tag>
      </Space>
    )}
    styles={compactAccountCardStyles}
  >
    {accounts.length === 0 ? (
      <Empty description="暂无皇冠账号" />
    ) : (
      <div>
        <div style={{ ...accountGridStyle, padding: '0 0 8px', borderBottom: '1px solid #f0f0f0' }}>
          <Text strong>账号信息</Text>
          <Text strong>账号状态</Text>
          <Text strong>账户余额</Text>
          <Text strong>检测结果</Text>
          <Text strong>AdsPower 环境</Text>
          <Text strong>操作</Text>
        </div>
        {accounts.map((account) => (
          <div key={account.id} style={{ ...accountGridStyle, ...accountRowStyle }}>
            <div style={valueBlockStyle}>
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                <Space size={8} wrap>
                  <Text strong>{account.displayName}</Text>
                  <Tag color={account.bettingEnabled === true ? 'success' : 'default'}>
                    {account.bettingEnabled === true ? '投注启用' : '投注停用'}
                  </Tag>
                </Space>
                <Text type="secondary">登录账号：{account.loginName}</Text>
                <a
                  href={account.loginUrl}
                  target="_blank"
                  rel="noreferrer"
                  style={{
                    display: 'inline-block',
                    maxWidth: '100%',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {account.loginUrl}
                </a>
              </Space>
            </div>

            <div>
              <Space direction="vertical" size={4}>
                <Tag color={statusMeta[account.status].color}>{statusMeta[account.status].label}</Tag>
                <Text type="secondary">更新：{formatDateTime(account.lastCheckedAt)}</Text>
              </Space>
            </div>

            <div>
              <Text strong={typeof account.balance === 'number'} type={typeof account.balance === 'number' ? undefined : 'secondary'}>
                {formatBalanceState(account)}
              </Text>
            </div>

            <div style={valueBlockStyle}>
              <Space direction="vertical" size={6}>
                <Text type={account.status === 'error' ? 'danger' : 'secondary'}>
                  {accountCheckSummary(account)}
                </Text>
                <Tag icon={<ApiOutlined />}>投注自动化使用 AdsPower 环境</Tag>
              </Space>
            </div>

            <div style={valueBlockStyle}>
              <Space direction="vertical" size={5} style={{ width: '100%' }}>
                <Tag color={adsPowerStatusColor(account)}>{adsPowerStatusLabel(account)}</Tag>
                {hasAdsPowerProfile(account) ? (
                  <Text
                    type="secondary"
                    style={{
                      display: 'inline-block',
                      maxWidth: '100%',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    AdsPower 档案 ID / 编号：{account.adsPowerProfileId}
                  </Text>
                ) : null}
                {account.adsPowerMessage && account.adsPowerStatus === 'error' ? (
                  <Text type="danger">{account.adsPowerMessage}</Text>
                ) : null}
              </Space>
            </div>

            <Space wrap size={[6, 4]}>
              <Switch
                size="small"
                checked={account.bettingEnabled === true}
                checkedChildren="启用"
                unCheckedChildren="停用"
                disabled={!hasAdsPowerProfile(account)}
                onChange={(checked) => updateAccount(account.id, { bettingEnabled: checked })}
              />
              <Button
                size="small"
                icon={<ApiOutlined />}
                loading={account.adsPowerStatus === 'starting'}
                disabled={!hasAdsPowerProfile(account)}
                onClick={() => openAdsPowerProfile(account)}
              >
                打开环境
              </Button>
              <Button
                size="small"
                loading={account.status === 'checking'}
                onClick={() => checkAccount(account)}
              >
                检测一次
              </Button>
              <Popconfirm
                title="删除账号"
                description="确认删除这个皇冠账号？"
                okText="删除"
                cancelText="取消"
                onConfirm={() => deleteAccount(account.id)}
              >
                <Button size="small" danger>删除账号</Button>
              </Popconfirm>
            </Space>
          </div>
        ))}
      </div>
    )}
  </Card>
)
