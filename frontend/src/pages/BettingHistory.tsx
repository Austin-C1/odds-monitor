import { useEffect, useMemo, useState } from 'react'
import { Card, Empty, Space, Statistic, Table, Tag, Typography, message } from 'antd'
import { apiClient } from '../services/api'

const { Text, Title } = Typography

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

const statusMeta: Record<string, { label: string; color: string }> = {
  placed: { label: '已下注', color: 'success' },
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

const BettingHistory = () => {
  const [rows, setRows] = useState<AutoBettingIntentRow[]>([])
  const [loading, setLoading] = useState(false)

  const loadRows = async () => {
    setLoading(true)
    try {
      const response = await apiClient.post<ApiResponse<AutoBettingIntentRow[]>>(
        '/auto-betting/intents/verified-placed',
        {},
      )
      if (response.data.code !== 0 || !Array.isArray(response.data.data)) {
        throw new Error(response.data.msg || '投注记录读取失败')
      }
      setRows(response.data.data)
    } catch (error: any) {
      message.error(error.response?.data?.msg || error.message || '投注记录读取失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadRows()
  }, [])

  const summary = useMemo(() => {
    return rows.reduce(
      (result, row) => ({
        successStake: result.successStake + Number(row.stakeAmount),
        successCount: result.successCount + 1,
        verifiedCount: result.verifiedCount + (row.crownHistoryVerified ? 1 : 0),
        rowCount: result.rowCount + 1,
      }),
      { successStake: 0, successCount: 0, verifiedCount: 0, rowCount: 0 },
    )
  }, [rows])

  return (
    <div>
      <Space align="center" style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
        <div>
          <Title level={2} style={{ margin: 0 }}>下注成功记录</Title>
          <Text type="secondary">只显示已完成下注，并通过皇冠投注历史二次验证的记录；判断通过但未下注不会进入这里。</Text>
        </div>
      </Space>

      <Space size={16} wrap style={{ marginBottom: 16 }}>
        <Card>
          <Statistic title="成功金额" value={summary.successStake} precision={2} prefix="¥" />
        </Card>
        <Card>
          <Statistic title="成功记录" value={summary.successCount} suffix="条" />
        </Card>
        <Card>
          <Statistic title="二次验证" value={summary.verifiedCount} suffix="条" />
        </Card>
      </Space>

      <Card title="下注成功记录" extra={<Tag color="green">皇冠历史已验证</Tag>}>
        {rows.length === 0 && !loading ? (
          <Empty description="暂无下注成功记录" />
        ) : (
          <Table
            rowKey={(record) => String(record.id ?? record.dedupeKey)}
            dataSource={rows}
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
            ]}
          />
        )}
      </Card>
    </div>
  )
}

export default BettingHistory
