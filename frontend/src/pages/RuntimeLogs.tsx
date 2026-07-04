import { useEffect, useState } from 'react'
import { Card, Table, Tag } from 'antd'
import { apiClient } from '../services/api'
import { PageShell } from './PageShell'

type ApiResponse<T> = { code: number; data: T; msg: string }
type RuntimeLog = {
  id: number
  sourceKey: string
  status: string
  message?: string
  startedAt: number
  finishedAt?: number
  recordsCount: number
  matchCount?: number
  marketCount?: number
  emptyMarketCount?: number
  failureReason?: string
}

const renderFailureReason = (record: RuntimeLog) => {
  if (record.status === 'success') {
    return '成功'
  }
  return record.failureReason || record.message || '-'
}

const RuntimeLogs = () => {
  const [rows, setRows] = useState<RuntimeLog[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    apiClient.post<ApiResponse<RuntimeLog[]>>('/odds-monitor/logs/list', {})
      .then((response) => setRows(response.data.data))
      .finally(() => setLoading(false))
  }, [])

  return (
    <PageShell
      title="运行日志"
      description="采集器启动后记录成功、失败和数据量。"
      actions={<Tag color="blue">采集器</Tag>}
      className="runtime-logs-page"
    >
      <Card>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        locale={{ emptyText: '暂无运行日志' }}
        columns={[
          { title: '数据源', dataIndex: 'sourceKey' },
          { title: '状态', dataIndex: 'status', render: (value: string) => <Tag>{value}</Tag> },
          { title: '错误分类', render: (_: unknown, record: RuntimeLog) => renderFailureReason(record) },
          { title: '开始时间', dataIndex: 'startedAt', render: (value: number) => new Date(value).toLocaleString('zh-CN') },
          { title: '结束时间', dataIndex: 'finishedAt', render: (value?: number) => value ? new Date(value).toLocaleString('zh-CN') : '-' },
          { title: '数量', dataIndex: 'recordsCount' },
          { title: '比赛', dataIndex: 'matchCount', render: (value?: number) => value ?? '-' },
          { title: '盘口', dataIndex: 'marketCount', render: (value?: number) => value ?? '-' },
          { title: '空盘口', dataIndex: 'emptyMarketCount', render: (value?: number) => value ?? '-' },
          { title: '说明', dataIndex: 'message', render: (value?: string) => value || '-' },
        ]}
      />
      </Card>
    </PageShell>
  )
}

export default RuntimeLogs
