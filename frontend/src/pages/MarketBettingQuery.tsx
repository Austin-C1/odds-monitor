import { useEffect, useState } from 'react'
import { Button, Card, Col, DatePicker, Empty, Form, Input, Row, Select, Space, Spin, Switch, Table, Tag, TimePicker, Typography, message } from 'antd'
import type { Dayjs } from 'dayjs'
import dayjs from 'dayjs'
import { ReloadOutlined, SaveOutlined, SearchOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { apiService } from '../services/api'
import type { MarketBettingEventDetail, MarketBettingEventSummary, MarketBettingMarketDetail, MarketBettingOutcomeDetail, NotificationConfig } from '../types'
import { extractTelegramConfig, normalizeChatIds } from './notificationSettingsHelpers'
import { displayMarketTitle, displayOutcomeName } from './marketBettingDisplay'

const { Title, Text } = Typography

type FormValues = {
  query: string
  date?: Dayjs | null
}

const formatUsdc = (value: string) => {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return `${value} USDC`
  return `${amount.toLocaleString(undefined, { maximumFractionDigits: 4 })} USDC`
}

const formatOdds = (value: string) => {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return value
  return `${(amount * 100).toFixed(2).replace(/\.?0+$/, '')}%`
}

const OutcomeList = ({ outcomes, marketTitle, marketType }: { outcomes: MarketBettingOutcomeDetail[]; marketTitle: string; marketType: string }) => {
  if (!outcomes.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无盘口明细" />
  return (
    <Table
      rowKey={(record) => record.tokenId || record.name}
      size="small"
      pagination={false}
      dataSource={outcomes}
      columns={[
        { title: '方向', dataIndex: 'name', width: 140, render: (value) => displayOutcomeName(value, marketTitle, marketType) },
        { title: '当前赔率', dataIndex: 'odds', width: 120, render: (value) => <Tag color="blue">{formatOdds(value)}</Tag> },
        { title: '方向成交额', dataIndex: 'tradedAmount', width: 140, render: formatUsdc },
        { title: '已成交 shares', dataIndex: 'tradedShares', width: 150, render: (value) => Number(value).toLocaleString(undefined, { maximumFractionDigits: 4 }) },
        { title: '买单额度', dataIndex: 'bidOrderAmount', width: 140, render: formatUsdc },
        { title: '卖单额度', dataIndex: 'askOrderAmount', width: 140, render: formatUsdc },
      ]}
    />
  )
}

const MarketBettingQuery: React.FC = () => {
  const [form] = Form.useForm<FormValues>()
  const [loading, setLoading] = useState(false)
  const [detailLoading, setDetailLoading] = useState(false)
  const [botSaving, setBotSaving] = useState(false)
  const [events, setEvents] = useState<MarketBettingEventSummary[]>([])
  const [detail, setDetail] = useState<MarketBettingEventDetail | null>(null)
  const [telegramConfigs, setTelegramConfigs] = useState<NotificationConfig[]>([])
  const [queryBotIds, setQueryBotIds] = useState<number[]>([])
  const [dailyReportEnabled, setDailyReportEnabled] = useState(false)
  const [dailyReportTime, setDailyReportTime] = useState('02:00')
  const [botLoadFailed, setBotLoadFailed] = useState(false)

  useEffect(() => {
    void loadTelegramConfigs()
  }, [])

  const loadTelegramConfigs = async () => {
    try {
      const response = await apiService.notifications.list({ type: 'telegram' })
      if (response.data.code === 0 && response.data.data) {
        const configs = response.data.data.filter((item) => item.type.toLowerCase() === 'telegram')
        const queryConfigs = configs.filter((item) => extractTelegramConfig(item).marketBettingQueryEnabled)
        const dailyConfig = queryConfigs.find((item) => extractTelegramConfig(item).marketBettingDailyReportEnabled) ?? queryConfigs[0]
        setTelegramConfigs(configs)
        setQueryBotIds(queryConfigs.map((item) => item.id!))
        setDailyReportEnabled(queryConfigs.some((item) => extractTelegramConfig(item).marketBettingDailyReportEnabled))
        setDailyReportTime(extractTelegramConfig(dailyConfig)?.marketBettingDailyReportTime || '02:00')
        setBotLoadFailed(false)
      }
    } catch {
      setTelegramConfigs([])
      setQueryBotIds([])
      setBotLoadFailed(true)
    }
  }

  const saveQueryBots = async () => {
    setBotSaving(true)
    try {
      await Promise.all(telegramConfigs.map((config) => {
        const telegram = extractTelegramConfig(config)
        return apiService.notifications.update({
          id: config.id!,
          type: config.type,
          name: config.name,
          enabled: config.enabled,
          config: {
            botToken: telegram.botToken || '',
            chatIds: normalizeChatIds(telegram.chatIds),
            monitorModeEnabled: Boolean(telegram.monitorModeEnabled),
            marketBettingQueryEnabled: queryBotIds.includes(config.id!),
            marketBettingDailyReportEnabled: queryBotIds.includes(config.id!) && dailyReportEnabled,
            marketBettingDailyReportTime: dailyReportTime,
            handicapCombinedWaterMin: telegram.handicapCombinedWaterMin,
            totalCombinedWaterMin: telegram.totalCombinedWaterMin,
            copyTradingLeaderGroups: telegram.copyTradingLeaderGroups || [],
            copyTradingCategories: telegram.copyTradingCategories || [],
            copyTradingNotificationTypes: telegram.copyTradingNotificationTypes || [],
          },
        })
      }))
      message.success('查询机器人已保存')
      await loadTelegramConfigs()
    } catch {
      message.error('保存查询机器人失败')
    } finally {
      setBotSaving(false)
    }
  }

  const search = async () => {
    const values = await form.validateFields()
    const date = values.date?.format('YYYY-MM-DD')
    setLoading(true)
    setDetail(null)
    try {
      const response = await apiService.marketBettingQuery.search({ query: values.query, limit: 8, date })
      if (response.data.code === 0 && response.data.data) {
        setEvents(response.data.data.events)
        if (response.data.data.events.length === 0) {
          message.warning('未找到相关盘口')
        }
      } else {
        message.error(response.data.msg || '查询失败')
      }
    } catch {
      message.error('查询失败')
    } finally {
      setLoading(false)
    }
  }

  const loadDetail = async (event: MarketBettingEventSummary) => {
    setDetailLoading(true)
    try {
      const values = form.getFieldsValue()
      const date = values.date?.format('YYYY-MM-DD')
      const response = await apiService.marketBettingQuery.detail({ slug: event.slug, marketLimit: 40, date })
      if (response.data.code === 0 && response.data.data) {
        setDetail(response.data.data)
      } else {
        message.error(response.data.msg || '加载盘口明细失败')
      }
    } catch {
      message.error('加载盘口明细失败')
    } finally {
      setDetailLoading(false)
    }
  }

  const eventColumns: ColumnsType<MarketBettingEventSummary> = [
    {
      title: '比赛 / 事件',
      dataIndex: 'title',
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Button type="link" style={{ padding: 0, height: 'auto', whiteSpace: 'normal', textAlign: 'left' }} onClick={() => void loadDetail(record)}>
            {record.title}
          </Button>
          <Text type="secondary" style={{ fontSize: 12 }}>{record.slug}</Text>
        </Space>
      ),
    },
    { title: '总成交额', dataIndex: 'volume', width: 150, render: formatUsdc },
    { title: '挂单金额', dataIndex: 'liquidity', width: 140, render: formatUsdc },
    { title: '盘口数', dataIndex: 'marketsCount', width: 90 },
    {
      title: '状态',
      dataIndex: 'closed',
      width: 90,
      render: (_, record) => record.closed ? <Tag>已关闭</Tag> : <Tag color={record.active ? 'success' : 'default'}>交易中</Tag>,
    },
  ]

  const marketColumns: ColumnsType<MarketBettingMarketDetail> = [
    {
      title: '盘口',
      dataIndex: 'question',
      render: (_, record) => (
        <Space direction="vertical" size={2}>
          <Text strong>{displayMarketTitle(record.groupItemTitle || record.question)}</Text>
          <Space size={6} wrap>
            <Tag>{record.marketType}{record.line ? ` ${record.line}` : ''}</Tag>
            <Text type="secondary">成交额 {formatUsdc(record.volume)}</Text>
            <Text type="secondary">挂单金额 {formatUsdc(record.liquidity)}</Text>
          </Space>
        </Space>
      ),
    },
  ]
  const visibleMarkets = detail?.markets ?? []

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Title level={2} style={{ margin: 0 }}>盘口投注额查询</Title>
      </div>

      <Card style={{ marginBottom: 16 }}>
        <Form form={form} layout="inline" onFinish={() => void search()}>
          <Form.Item name="query" rules={[{ required: true, message: '请输入比赛名或盘口名' }]} style={{ flex: 1, minWidth: 280 }}>
            <Input placeholder="例如 Wild vs Stars、Trump、World Cup Winner" allowClear />
          </Form.Item>
          <Form.Item name="date">
            <DatePicker allowClear placeholder="选择比赛日期" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={loading}>查询</Button>
              <Button icon={<ReloadOutlined />} onClick={() => void search()} disabled={loading}>刷新</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card title="查询机器人" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            mode="multiple"
            allowClear
            style={{ minWidth: 320 }}
            value={queryBotIds}
            options={telegramConfigs.map((item) => ({ label: item.name, value: item.id }))}
            placeholder="选择接收盘口查询指令的机器人"
            onChange={setQueryBotIds}
          />
          <Button type="primary" icon={<SaveOutlined />} loading={botSaving} onClick={() => void saveQueryBots()}>
            保存
          </Button>
        </Space>
        <div style={{ marginTop: 8 }}>
          <Text type="secondary">只有选中的机器人会响应 Telegram 里的盘口查询指令。</Text>
        </div>
        {botLoadFailed && (
          <div style={{ marginTop: 8 }}>
            <Text type="warning">查询机器人配置暂时不可用，手动 Polymarket 查询仍可正常使用。</Text>
          </div>
        )}
        <div style={{ marginTop: 16 }}>
          <Space wrap align="center">
            <Text strong>每日自动推送</Text>
            <Switch checked={dailyReportEnabled} onChange={setDailyReportEnabled} />
            <TimePicker
              format="HH:mm"
              minuteStep={5}
              value={dayjs(`2026-01-01 ${dailyReportTime}`)}
              disabled={!dailyReportEnabled}
              onChange={(value) => setDailyReportTime(value?.format('HH:mm') || '02:00')}
            />
            <Text type="secondary">北京时间，自动推送未结束足球和篮球比赛的总成交额、挂单金额。</Text>
          </Space>
        </div>
      </Card>

      <Row gutter={[16, 16]}>
        <Col span={24}>
          <Card title="搜索结果">
            <Spin spinning={loading}>
              <Table rowKey="slug" columns={eventColumns} dataSource={events} pagination={false} />
            </Spin>
          </Card>
        </Col>

        <Col span={24}>
          <Card
            title={detail ? detail.event.title : '盘口明细'}
            extra={detail ? <a href={detail.event.url} target="_blank" rel="noreferrer">打开 Polymarket</a> : null}
          >
            <Spin spinning={detailLoading}>
              {detail ? (
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <Space wrap>
                    <Tag color="blue">总成交额 {formatUsdc(detail.event.volume)}</Tag>
                    <Tag color="green">总挂单金额 {formatUsdc(detail.event.liquidity)}</Tag>
                    <Tag>盘口数 {visibleMarkets.length}</Tag>
                  </Space>
                  <Table
                    rowKey={(record) => record.conditionId || record.id}
                    columns={marketColumns}
                    dataSource={visibleMarkets}
                    pagination={{ pageSize: 8 }}
                    expandable={{ expandedRowRender: (record) => <OutcomeList outcomes={record.outcomes} marketTitle={record.groupItemTitle || record.question} marketType={record.marketType} /> }}
                  />
                </Space>
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="点击搜索结果查看盘口、赔率、方向成交额和挂单额" />
              )}
            </Spin>
          </Card>
        </Col>
      </Row>
    </div>
  )
}

export default MarketBettingQuery
