import { useEffect, useState } from 'react'
import { Card, Table, Tag, Typography } from 'antd'
import { apiClient } from '../services/api'

const { Title, Text } = Typography

type ApiResponse<T> = { code: number; data: T; msg: string }
type RuntimeLog = {
  id: number
  sourceKey: string
  status: string
  message?: string
  startedAt: number
  finishedAt?: number
  recordsCount: number
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
    <Card>
      <Title level={3} style={{ marginTop: 0 }}>运行日志</Title>
      <Text type="secondary">采集器启动后记录成功、失败和数据量。</Text>
      <Table
        style={{ marginTop: 16 }}
        rowKey="id"
        loading={loading}
        dataSource={rows}
        locale={{ emptyText: '暂无运行日志' }}
        columns={[
          { title: '数据源', dataIndex: 'sourceKey' },
          { title: '状态', dataIndex: 'status', render: (value: string) => <Tag>{value}</Tag> },
          { title: '开始时间', dataIndex: 'startedAt', render: (value: number) => new Date(value).toLocaleString('zh-CN') },
          { title: '结束时间', dataIndex: 'finishedAt', render: (value?: number) => value ? new Date(value).toLocaleString('zh-CN') : '-' },
          { title: '数量', dataIndex: 'recordsCount' },
          { title: '说明', dataIndex: 'message', render: (value?: string) => value || '-' },
        ]}
      />
    </Card>
  )
}

export default RuntimeLogs
