import { useEffect, useMemo, useState } from 'react'
import { Button, Card, Empty, Form, Input, Modal, Popconfirm, Space, Statistic, Switch, Table, Tag, Typography, message } from 'antd'
import { DeleteOutlined, PlusOutlined, SendOutlined } from '@ant-design/icons'
import { apiClient } from '../services/api'
import { TelegramConfigForm } from '../components/notifications'
import type { NotificationConfig } from '../types'
import { extractApiErrorMessage } from '../utils/apiError'
import { PageShell } from './PageShell'
import { extractTelegramConfig, isTelegramConfigReadyForTest, normalizeChatIds } from './notificationSettingsHelpers'

const { Text } = Typography

type ApiResponse<T> = { code: number; data: T; msg?: string }

type AutoBettingIntentRow = {
  id?: number | null
  status: string
  reason: string
  dedupeKey: string
  signalSource: string
  bettingMode: string
  matchPhase: string
  accountKey: string
  accountDisplayName?: string | null
  leagueName: string
  matchTitle: string
  marketType: string
  lineValue?: string | null
  selectionName: string
  referenceSourceKey: string
  targetSourceKey: string
  referenceOdds: number
  targetOdds: number
  targetDecimalOdds: number
  decimalEdge: number
  stakeAmount: number
  capturedAt: number
  createdAt: number
  crownHistoryVerified: boolean
  crownHistoryCheckedAt?: number | null
  crownBetReference?: string | null
}

type StoredCrownAccount = {
  id: string
  displayName: string
  loginName?: string
  loginUrl?: string
  adsPowerProfileId?: string
}

const ACCOUNTS_STORAGE_KEY = 'crown-betting-accounts'
const TELEGRAM_BETTING_SUCCESS_TYPE = 'telegram_betting_success'
const BETTING_SUCCESS_TEST_MESSAGE = '<b>投注成功测试</b>\n账号：测试账号\n盘口：让球 0.5/1\n赔率：0.930\n金额：50.00'

type BettingSuccessBotFormValues = {
  name: string
  enabled: boolean
  config: {
    botToken: string
    chatIds: string | string[]
  }
}

const statusMeta: Record<string, { label: string; color: string }> = {
  ready: { label: '待执行', color: 'processing' },
  placing: { label: '执行中', color: 'blue' },
  placed: { label: '已下注', color: 'success' },
  placed_unverified: { label: '待确认', color: 'warning' },
  rejected: { label: '失败/跳过', color: 'error' },
}

const marketLabel = (value: string) => {
  if (value === 'handicap') return '让球盘'
  if (value === 'total') return '大小球'
  return value
}

const phaseLabel = (value?: string) => {
  if (value === 'prematch') return '赛前'
  if (value === 'live') return '滚球'
  return value || '-'
}

const formatCurrency = (value: number) =>
  new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
  }).format(value)

const formatDateTime = (value: number) =>
  new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })

const formatOdds = (value: number) => Number(value).toFixed(3)

const readStoredCrownAccounts = (): StoredCrownAccount[] => {
  try {
    const raw = localStorage.getItem(ACCOUNTS_STORAGE_KEY)
    const parsed = raw ? JSON.parse(raw) as StoredCrownAccount[] : []
    return Array.isArray(parsed)
      ? parsed.filter((account) => account?.id && account.displayName)
      : []
  } catch {
    return []
  }
}

const BettingHistory = () => {
  const [botForm] = Form.useForm<BettingSuccessBotFormValues>()
  const [rows, setRows] = useState<AutoBettingIntentRow[]>([])
  const [importedAccounts, setImportedAccounts] = useState<StoredCrownAccount[]>(readStoredCrownAccounts)
  const [botConfigs, setBotConfigs] = useState<NotificationConfig[]>([])
  const [botModalOpen, setBotModalOpen] = useState(false)
  const [botLoading, setBotLoading] = useState(false)
  const [testBotId, setTestBotId] = useState<number | null>(null)
  const [selectedAccountId, setSelectedAccountId] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [showAllStatuses, setShowAllStatuses] = useState(false)

  const loadBotConfigs = async () => {
    setBotLoading(true)
    try {
      const response = await apiClient.post<ApiResponse<NotificationConfig[]>>(
        '/system/notifications/configs/list',
        { type: TELEGRAM_BETTING_SUCCESS_TYPE },
      )
      if (response.data.code !== 0 || !Array.isArray(response.data.data)) {
        throw new Error(response.data.msg || '投注成功机器人读取失败')
      }
      setBotConfigs(response.data.data)
    } catch (error: any) {
      message.error(extractApiErrorMessage(error, '投注成功机器人读取失败'))
    } finally {
      setBotLoading(false)
    }
  }

  const loadRows = async () => {
    setLoading(true)
    try {
      const accounts = readStoredCrownAccounts()
      setImportedAccounts(accounts)
      const response = await apiClient.post<ApiResponse<AutoBettingIntentRow[]>>(
        showAllStatuses ? '/auto-betting/intents/recent' : '/auto-betting/intents/verified-placed',
        {},
      )
      if (response.data.code !== 0 || !Array.isArray(response.data.data)) {
        throw new Error(response.data.msg || '投注记录读取失败')
      }
      setRows(response.data.data)
    } catch (error: any) {
      message.error(extractApiErrorMessage(error, '投注记录读取失败'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadRows()
    void loadBotConfigs()
  }, [showAllStatuses])

  const openBotModal = () => {
    botForm.resetFields()
    botForm.setFieldsValue({
      name: '投注成功机器人',
      enabled: true,
      config: {
        botToken: '',
        chatIds: '',
      },
    })
    setBotModalOpen(true)
  }

  const handleCreateBot = async () => {
    try {
      const values = await botForm.validateFields()
      const response = await apiClient.post<ApiResponse<NotificationConfig>>(
        '/system/notifications/configs/create',
        {
          type: TELEGRAM_BETTING_SUCCESS_TYPE,
          name: values.name,
          enabled: values.enabled,
          config: {
            botToken: values.config.botToken?.trim(),
            chatIds: normalizeChatIds(values.config.chatIds),
          },
        },
      )
      if (response.data.code !== 0) {
        throw new Error(response.data.msg || '投注成功机器人添加失败')
      }
      message.success('投注成功机器人已添加')
      setBotModalOpen(false)
      void loadBotConfigs()
    } catch (error: any) {
      if (error?.errorFields) return
      message.error(extractApiErrorMessage(error, '投注成功机器人添加失败'))
    }
  }

  const handleUpdateBotEnabled = async (id: number, enabled: boolean) => {
    try {
      const response = await apiClient.post<ApiResponse<NotificationConfig>>(
        '/system/notifications/configs/update-enabled',
        { id, enabled },
      )
      if (response.data.code !== 0) {
        throw new Error(response.data.msg || '投注成功机器人状态更新失败')
      }
      void loadBotConfigs()
    } catch (error: any) {
      message.error(extractApiErrorMessage(error, '投注成功机器人状态更新失败'))
    }
  }

  const handleDeleteBot = async (id: number) => {
    try {
      const response = await apiClient.post<ApiResponse<void>>(
        '/system/notifications/configs/delete',
        { id },
      )
      if (response.data.code !== 0) {
        throw new Error(response.data.msg || '投注成功机器人删除失败')
      }
      message.success('投注成功机器人已删除')
      void loadBotConfigs()
    } catch (error: any) {
      message.error(extractApiErrorMessage(error, '投注成功机器人删除失败'))
    }
  }

  const handleTestBot = async (config: NotificationConfig) => {
    if (!config.id) return
    setTestBotId(config.id)
    try {
      const response = await apiClient.post<ApiResponse<boolean>>(
        '/system/notifications/test',
        { configId: config.id, message: BETTING_SUCCESS_TEST_MESSAGE },
      )
      if (response.data.code !== 0 || response.data.data !== true) {
        throw new Error(response.data.msg || '投注成功机器人测试失败')
      }
      message.success('投注成功机器人测试已发送')
    } catch (error: any) {
      message.error(extractApiErrorMessage(error, '投注成功机器人测试失败'))
    } finally {
      setTestBotId(null)
    }
  }

  const accountNameById = useMemo(() => (
    new Map(importedAccounts.map((account) => [account.id, account.displayName]))
  ), [importedAccounts])

  const accountCountsById = useMemo(() => {
    return rows.reduce((result, row) => {
      result.set(row.accountKey, (result.get(row.accountKey) || 0) + 1)
      return result
    }, new Map<string, number>())
  }, [rows])

  const filteredRows = useMemo(() => (
    selectedAccountId ? rows.filter((row) => row.accountKey === selectedAccountId) : rows
  ), [rows, selectedAccountId])

  const summary = useMemo(() => {
    return filteredRows.reduce(
      (result, row) => {
        const success = row.status === 'placed' && row.crownHistoryVerified === true
        return {
          successStake: result.successStake + (success ? Number(row.stakeAmount) : 0),
          successCount: result.successCount + (success ? 1 : 0),
          verifiedCount: result.verifiedCount + (row.crownHistoryVerified ? 1 : 0),
          rowCount: result.rowCount + 1,
        }
      },
      { successStake: 0, successCount: 0, verifiedCount: 0, rowCount: 0 },
    )
  }, [filteredRows])

  return (
    <PageShell
      title="下注记录"
      description="投注成功后自动写入这里；默认只显示已验证成功的下注，失败、冷却、重复等只在全状态记录里查看。"
      className="betting-history-page"
    >
      <div className="page-stat-grid">
        <Card>
          <Statistic title="成功金额" value={summary.successStake} precision={2} prefix="¥" />
        </Card>
        <Card>
          <Statistic title="成功记录" value={summary.successCount} suffix="条" />
        </Card>
        <Card>
          <Statistic title="二次验证" value={summary.verifiedCount} suffix="条" />
        </Card>
      </div>

      <Card
        size="small"
        title="投注成功机器人"
        extra={(
          <Button type="primary" size="small" icon={<PlusOutlined />} onClick={openBotModal}>
            添加机器人
          </Button>
        )}
      >
        <Text type="secondary">
          这里只负责下注成功后的 TG 推送，不影响告警通知页面里的采集机器人。
        </Text>
        {botConfigs.length === 0 && !botLoading ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无投注成功机器人" style={{ marginTop: 12 }} />
        ) : (
          <Table
            size="small"
            rowKey={(record) => String(record.id)}
            dataSource={botConfigs}
            loading={botLoading}
            pagination={false}
            style={{ marginTop: 12 }}
            columns={[
              {
                title: '机器人',
                dataIndex: 'name',
                render: (value: string, record) => (
                  <Space direction="vertical" size={2}>
                    <Text strong>{value}</Text>
                    <Tag color={record.enabled ? 'green' : 'default'}>
                      {record.enabled ? '已启用' : '已停用'}
                    </Tag>
                  </Space>
                ),
              },
              {
                title: 'Chat IDs',
                render: (_value: unknown, record) => {
                  const chatIds = normalizeChatIds(extractTelegramConfig(record).chatIds)
                  return chatIds.length > 0 ? chatIds.join(', ') : '-'
                },
              },
              {
                title: '操作',
                width: 220,
                render: (_value: unknown, record) => (
                  <Space size="small" wrap>
                    <Switch
                      size="small"
                      checked={record.enabled}
                      onChange={(checked) => record.id && void handleUpdateBotEnabled(record.id, checked)}
                    />
                    <Button
                      type="link"
                      size="small"
                      icon={<SendOutlined />}
                      disabled={!isTelegramConfigReadyForTest(record)}
                      loading={testBotId === record.id}
                      onClick={() => void handleTestBot(record)}
                    >
                      测试
                    </Button>
                    <Popconfirm
                      title="删除投注成功机器人"
                      description="删除后投注成功不会再发给这个机器人。"
                      okText="删除"
                      cancelText="取消"
                      onConfirm={() => record.id && void handleDeleteBot(record.id)}
                    >
                      <Button type="link" danger size="small" icon={<DeleteOutlined />}>
                        删除
                      </Button>
                    </Popconfirm>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Card size="small" title="已导入账号">
        <Space wrap>
          <Button
            type={selectedAccountId === null ? 'primary' : 'default'}
            onClick={() => setSelectedAccountId(null)}
          >
            全部账号 <Tag style={{ marginInlineStart: 6 }}>{rows.length}</Tag>
          </Button>
          {importedAccounts.map((account) => (
            <Button
              key={account.id}
              type={selectedAccountId === account.id ? 'primary' : 'default'}
              onClick={() => setSelectedAccountId(account.id)}
            >
              {account.displayName}
              <Tag style={{ marginInlineStart: 6 }}>{accountCountsById.get(account.id) || 0}</Tag>
            </Button>
          ))}
        </Space>
      </Card>

      <Modal
        title="添加投注成功机器人"
        open={botModalOpen}
        okText="添加"
        cancelText="取消"
        onOk={handleCreateBot}
        onCancel={() => setBotModalOpen(false)}
        destroyOnHidden
      >
        <Form form={botForm} layout="vertical" preserve={false}>
          <Form.Item
            name="name"
            label="机器人名称"
            rules={[{ required: true, message: '请输入机器人名称' }]}
          >
            <Input placeholder="例如：投注成功机器人" />
          </Form.Item>
          <Form.Item name="enabled" valuePropName="checked" label="启用">
            <Switch />
          </Form.Item>
          <TelegramConfigForm form={botForm} />
        </Form>
      </Modal>

      <Card
        title="下注记录"
        extra={(
          <Button size="small" onClick={() => setShowAllStatuses((value) => !value)}>
            {showAllStatuses ? '仅验证记录' : '全状态记录'}
          </Button>
        )}
      >
        {filteredRows.length === 0 && !loading ? (
          <Empty description="暂无下注记录" />
        ) : (
          <Table
            rowKey={(record) => String(record.id ?? record.dedupeKey)}
            dataSource={filteredRows}
            loading={loading}
            pagination={{ pageSize: 20 }}
            scroll={{ x: 1080 }}
            columns={[
              {
                title: '时间',
                dataIndex: 'createdAt',
                width: 140,
                render: (value: number) => formatDateTime(value),
              },
              {
                title: '账号',
                dataIndex: 'accountKey',
                width: 120,
                render: (value: string, record) => accountNameById.get(value) || record.accountDisplayName || value,
              },
              {
                title: '模式',
                dataIndex: 'matchPhase',
                width: 88,
                render: (value: string) => (
                  <Tag color={value === 'prematch' ? 'blue' : 'orange'}>{phaseLabel(value)}</Tag>
                ),
              },
              {
                title: '比赛',
                render: (_value: unknown, record) => (
                  <Space direction="vertical" size={0}>
                    <Text strong>{record.matchTitle}</Text>
                    <Text type="secondary">{record.leagueName}</Text>
                  </Space>
                ),
              },
              {
                title: '盘口',
                render: (_value: unknown, record) => (
                  <Space direction="vertical" size={0}>
                    <Text>{marketLabel(record.marketType)} {record.lineValue || '-'}</Text>
                    <Text type="secondary">{record.selectionName}</Text>
                  </Space>
                ),
              },
              {
                title: '赔率',
                align: 'right' as const,
                render: (_value: unknown, record) => (
                  <Space direction="vertical" size={0}>
                    <Text>{formatOdds(record.targetOdds)}</Text>
                    <Text type="secondary">优势 {formatOdds(record.decimalEdge)}</Text>
                  </Space>
                ),
              },
              {
                title: '下注金额',
                dataIndex: 'stakeAmount',
                align: 'right' as const,
                render: (value: number) => formatCurrency(value),
              },
              {
                title: '状态',
                dataIndex: 'status',
                render: (value: string) => {
                  const meta = statusMeta[value] || { label: value, color: 'default' }
                  return <Tag color={meta.color}>{meta.label}</Tag>
                },
              },
              {
                title: '二次验证',
                dataIndex: 'crownHistoryVerified',
                render: (value: boolean, record) => (
                  <Space direction="vertical" size={0}>
                    <Tag color={value ? 'success' : 'error'}>{value ? '已验证' : '未验证'}</Tag>
                    <Text type="secondary">
                      {record.crownHistoryCheckedAt ? formatDateTime(record.crownHistoryCheckedAt) : '-'}
                    </Text>
                  </Space>
                ),
              },
              {
                title: '皇冠记录',
                dataIndex: 'crownBetReference',
                width: 180,
                render: (value?: string | null) => value || '-',
              },
              {
                title: '原因',
                dataIndex: 'reason',
                width: 220,
                render: (value?: string | null) => value || '-',
              },
            ]}
          />
        )}
      </Card>
    </PageShell>
  )
}

export default BettingHistory
