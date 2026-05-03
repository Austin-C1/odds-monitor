import { useEffect, useState } from 'react'
import { Button, Card, Checkbox, Form, InputNumber, Select, Space, Spin, Switch, Table, Tag, Typography, message } from 'antd'
import { ReloadOutlined, SaveOutlined, SendOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { apiService } from '../services/api'
import type {
  LargeBetMonitorConfig,
  LargeBetMonitorConfigUpdateRequest,
  LargeBetMonitorStatus,
  LargeBetWatchRecord,
  NotificationConfig,
} from '../types'

const { Title, Text } = Typography

type FormValues = {
  enabled: boolean
  sports: string[]
  singleTradeThreshold: number
  cumulativeTradeThreshold: number
  rollingWindowMinutes: number
  checkIntervalSeconds: number
  telegramConfigId?: number | null
}

const LargeBetMonitor: React.FC = () => {
  const [form] = Form.useForm<FormValues>()
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [records, setRecords] = useState<LargeBetWatchRecord[]>([])
  const [status, setStatus] = useState<LargeBetMonitorStatus | null>(null)
  const [telegramConfigs, setTelegramConfigs] = useState<NotificationConfig[]>([])

  useEffect(() => {
    void load()
  }, [])

  const applyConfig = (config: LargeBetMonitorConfig) => {
    form.setFieldsValue({
      enabled: config.enabled,
      sports: [
        ...(config.footballEnabled ? ['FOOTBALL'] : []),
        ...(config.basketballEnabled ? ['BASKETBALL'] : []),
      ],
      singleTradeThreshold: Number(config.singleTradeThreshold),
      cumulativeTradeThreshold: Number(config.cumulativeTradeThreshold),
      rollingWindowMinutes: config.rollingWindowMinutes,
      checkIntervalSeconds: config.checkIntervalSeconds,
      telegramConfigId: config.telegramConfigId ?? null,
    })
  }

  const load = async () => {
    setLoading(true)
    try {
      const [configResponse, recordsResponse, statusResponse, notificationResponse] = await Promise.all([
        apiService.largeBetMonitor.getConfig(),
        apiService.largeBetMonitor.listRecords(),
        apiService.largeBetMonitor.getStatus(),
        apiService.notifications.list({ type: 'telegram' }),
      ])

      if (configResponse.data.code === 0 && configResponse.data.data) {
        applyConfig(configResponse.data.data)
      }
      if (recordsResponse.data.code === 0 && recordsResponse.data.data) {
        setRecords(recordsResponse.data.data)
      }
      if (statusResponse.data.code === 0 && statusResponse.data.data) {
        setStatus(statusResponse.data.data)
      }
      if (notificationResponse.data.code === 0 && notificationResponse.data.data) {
        setTelegramConfigs(notificationResponse.data.data.filter((item) => item.type.toLowerCase() === 'telegram'))
      }
    } catch {
      message.error('加载大额投注监控失败')
    } finally {
      setLoading(false)
    }
  }

  const save = async () => {
    const values = await form.validateFields()
    const sports = values.sports || []
    const payload: LargeBetMonitorConfigUpdateRequest = {
      enabled: values.enabled,
      footballEnabled: sports.includes('FOOTBALL'),
      basketballEnabled: sports.includes('BASKETBALL'),
      singleTradeThreshold: String(values.singleTradeThreshold),
      cumulativeTradeThreshold: String(values.cumulativeTradeThreshold),
      rollingWindowMinutes: values.rollingWindowMinutes,
      checkIntervalSeconds: values.checkIntervalSeconds,
      telegramConfigId: values.telegramConfigId ?? null,
    }

    setSaving(true)
    try {
      const response = await apiService.largeBetMonitor.updateConfig(payload)
      if (response.data.code === 0 && response.data.data) {
        applyConfig(response.data.data)
        message.success('配置已保存')
        await load()
      } else {
        message.error(response.data.msg || '保存失败')
      }
    } catch {
      message.error('保存失败')
    } finally {
      setSaving(false)
    }
  }

  const testPush = async () => {
    setTesting(true)
    try {
      const response = await apiService.largeBetMonitor.test()
      if (response.data.code === 0) {
        message.success('测试消息已发送')
      } else {
        message.error(response.data.msg || '测试失败')
      }
    } catch {
      message.error('测试失败')
    } finally {
      setTesting(false)
    }
  }

  const columns: ColumnsType<LargeBetWatchRecord> = [
    {
      title: '用户',
      dataIndex: 'traderName',
      render: (_, record) => (
        <a href={record.profileUrl} target="_blank" rel="noreferrer">
          {record.traderName || `${record.traderAddress.slice(0, 6)}...${record.traderAddress.slice(-4)}`}
        </a>
      ),
    },
    {
      title: '盘口',
      dataIndex: 'marketTitle',
      render: (_, record) => {
        const href = record.marketSlug
          ? `https://polymarket.com/event/${record.marketSlug}`
          : `https://polymarket.com/condition/${record.marketId}`
        return <a href={href} target="_blank" rel="noreferrer">{record.marketTitle}</a>
      },
    },
    {
      title: '类型',
      dataIndex: 'sportType',
      render: (value) => <Tag color={value === 'FOOTBALL' ? 'green' : 'blue'}>{value}</Tag>,
    },
    { title: '方向', dataIndex: 'outcome' },
    { title: '原因', dataIndex: 'triggerReason' },
    { title: '单笔成交', dataIndex: 'lastSingleAmount' },
    { title: '窗口累计', dataIndex: 'lastCumulativeAmount' },
    { title: '次数', dataIndex: 'triggerCount' },
    {
      title: '最后触发',
      dataIndex: 'lastTriggeredAt',
      render: (value) => new Date(value).toLocaleString(),
    },
  ]

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Title level={2} style={{ margin: 0 }}>大额投注监控</Title>
      </div>

      <Spin spinning={loading}>
        <Card
          title="监控配置"
          extra={
            <Space>
              <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
              <Button icon={<SendOutlined />} onClick={() => void testPush()} loading={testing}>测试推送</Button>
              <Button type="primary" icon={<SaveOutlined />} onClick={() => void save()} loading={saving}>保存</Button>
            </Space>
          }
          style={{ marginBottom: 16 }}
        >
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Space size="large" wrap>
              <Text>状态: {status?.enabled ? <Tag color="success">已启用</Tag> : <Tag>已停用</Tag>}</Text>
              <Text>连接: {status?.connected ? <Tag color="success">已连接</Tag> : <Tag>未连接</Tag>}</Text>
              <Text>跟踪桶: {status?.trackedBuckets ?? 0}</Text>
            </Space>

            <Form
              form={form}
              layout="vertical"
              initialValues={{
                enabled: false,
                sports: ['FOOTBALL', 'BASKETBALL'],
                singleTradeThreshold: 5000,
                cumulativeTradeThreshold: 15000,
                rollingWindowMinutes: 60,
                checkIntervalSeconds: 30,
                telegramConfigId: null,
              }}
            >
              <Form.Item name="enabled" label="启用监控" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item
                name="sports"
                label="监控盘口"
                rules={[{ validator: (_, value) => value?.length ? Promise.resolve() : Promise.reject(new Error('至少选择一个盘口类型')) }]}
              >
                <Checkbox.Group
                  options={[
                    { label: '足球', value: 'FOOTBALL' },
                    { label: '篮球', value: 'BASKETBALL' },
                  ]}
                />
              </Form.Item>
              <Space size="large" wrap>
                <Form.Item name="singleTradeThreshold" label="单笔成交金额" rules={[{ required: true }, { type: 'number', min: 0.00000001 }]}>
                  <InputNumber min={0.00000001} precision={2} addonAfter="USDC" />
                </Form.Item>
                <Form.Item name="cumulativeTradeThreshold" label="累计成交金额" rules={[{ required: true }, { type: 'number', min: 0.00000001 }]}>
                  <InputNumber min={0.00000001} precision={2} addonAfter="USDC" />
                </Form.Item>
                <Form.Item name="rollingWindowMinutes" label="时间窗口" rules={[{ required: true }, { type: 'number', min: 1, max: 1440 }]}>
                  <InputNumber min={1} max={1440} addonAfter="分钟" />
                </Form.Item>
                <Form.Item name="checkIntervalSeconds" label="检查间隔" rules={[{ required: true }, { type: 'number', min: 5, max: 3600 }]}>
                  <InputNumber min={5} max={3600} addonAfter="秒" />
                </Form.Item>
                <Form.Item name="telegramConfigId" label="Telegram 配置">
                  <Select
                    allowClear
                    style={{ minWidth: 220 }}
                    options={telegramConfigs.map((item) => ({ label: item.name, value: item.id }))}
                    placeholder="使用监控专用配置"
                  />
                </Form.Item>
              </Space>
            </Form>
          </Space>
        </Card>

        <Card title="触发备案">
          <Table
            rowKey={(record) => `${record.traderAddress}-${record.marketId}-${record.outcome}`}
            columns={columns}
            dataSource={records}
            pagination={{ pageSize: 20 }}
            scroll={{ x: 1200 }}
          />
        </Card>
      </Spin>
    </div>
  )
}

export default LargeBetMonitor
