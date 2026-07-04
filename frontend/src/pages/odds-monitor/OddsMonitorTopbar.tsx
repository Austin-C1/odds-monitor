import { Button, Checkbox, Space, Tag, Typography } from 'antd'

const { Text } = Typography

type OddsMonitorTopbarProps = {
  matchCount: number
}

export const OddsMonitorTopbar = ({ matchCount }: OddsMonitorTopbarProps) => (
  <header className="odds-monitor-topbar">
    <Space align="center" wrap>
      <span className="page-title">全平台赔率监控</span>
      <Tag color="blue">{matchCount} 场比赛</Tag>
      <Tag color="green">盘口折叠</Tag>
    </Space>
    <Space align="center" wrap className="topbar-actions">
      {['1小时', '2小时', '6小时', '全天'].map((label) => (
        <Button key={label} size="small" type={label === '全天' ? 'primary' : 'default'}>{label}</Button>
      ))}
      <Checkbox checked>自动刷新</Checkbox>
      <Text type="secondary">更新：{new Date().toLocaleString('zh-CN')}</Text>
    </Space>
  </header>
)
