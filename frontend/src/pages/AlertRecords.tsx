import { useEffect, useState } from 'react'
import { Card, Table, Tag, Typography } from 'antd'
import { apiClient } from '../services/api'

const { Title, Text } = Typography

type ApiResponse<T> = { code: number; data: T; msg: string }
type AlertRecord = {
  id: number
  alertType: string
  severity: string
  matchName?: string
  sourceKey?: string
  title: string
  message: string
  createdAt: number
  acknowledged: boolean
}

const AlertRecords = () => {
  const [rows, setRows] = useState<AlertRecord[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    apiClient.post<ApiResponse<AlertRecord[]>>('/odds-monitor/alerts/list', {})
      .then((response) => setRows(response.data.data))
      .finally(() => setLoading(false))
  }, [])

  return (
    <Card>
      <Title level={3} style={{ marginTop: 0 }}>告警记录</Title>
      <Text type="secondary">底座阶段预留盘口变化、赔率快速变化、平台差异、Polymarket 偏离和数据源异常。</Text>
      <Table
        style={{ marginTop: 16 }}
        rowKey="id"
        loading={loading}
        dataSource={rows}
        locale={{ emptyText: '暂无告警记录' }}
        columns={[
          { title: '类型', dataIndex: 'alertType' },
          { title: '级别', dataIndex: 'severity', render: (value: string) => <Tag>{value}</Tag> },
          { title: '比赛', dataIndex: 'matchName', render: (value?: string) => value || '-' },
          { title: '数据源', dataIndex: 'sourceKey', render: (value?: string) => value || '-' },
          { title: '标题', dataIndex: 'title' },
          { title: '内容', dataIndex: 'message' },
          { title: '时间', dataIndex: 'createdAt', render: (value: number) => new Date(value).toLocaleString('zh-CN') },
        ]}
      />
    </Card>
  )
}

export default AlertRecords
