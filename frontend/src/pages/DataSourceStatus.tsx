import { useEffect, useState } from 'react'
import { Card, Table, Tag, Typography } from 'antd'
import { apiClient } from '../services/api'

const { Title, Text } = Typography

type ApiResponse<T> = { code: number; data: T; msg: string }
type DataSourceStatus = {
  sourceKey: string
  displayName: string
  enabled: boolean
  currentStatus: string
  lastCollectTime?: number
  lastSuccessTime?: number
  lastFailureTime?: number
  failureReason?: string
}

const formatTime = (value?: number) => value ? new Date(value).toLocaleString('zh-CN') : '-'

const statusLabels: Record<string, { text: string; color: string }> = {
  success: { text: '采集成功', color: 'green' },
  waiting: { text: '等待采集', color: 'blue' },
  disabled: { text: '未启用', color: 'default' },
  failed_login: { text: '登录失败', color: 'red' },
  failed_config: { text: '配置错误', color: 'orange' },
  failed_empty: { text: '无赔率数据', color: 'orange' },
  failed_http: { text: '接口失败', color: 'red' },
  failed_runtime: { text: '运行失败', color: 'red' },
}

const renderStatus = (status: string) => {
  const item = statusLabels[status] || { text: status, color: 'default' }
  return <Tag color={item.color}>{item.text}</Tag>
}

const DataSourceStatus = () => {
  const [rows, setRows] = useState<DataSourceStatus[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    apiClient.post<ApiResponse<DataSourceStatus[]>>('/odds-monitor/data-sources/status/list', {})
      .then((response) => setRows(response.data.data))
      .finally(() => setLoading(false))
  }, [])

  return (
    <Card>
      <Title level={3} style={{ marginTop: 0 }}>数据源状态</Title>
      <Text type="secondary">显示各平台最近一次采集状态。真实采集器接入后这里会自动更新。</Text>
      <Table
        style={{ marginTop: 16 }}
        rowKey="sourceKey"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: '数据源', dataIndex: 'displayName' },
          { title: '启用', dataIndex: 'enabled', render: (enabled: boolean) => enabled ? <Tag color="green">启用</Tag> : <Tag>未启用</Tag> },
          { title: '当前状态', dataIndex: 'currentStatus', render: renderStatus },
          { title: '最近采集', dataIndex: 'lastCollectTime', render: formatTime },
          { title: '最近成功', dataIndex: 'lastSuccessTime', render: formatTime },
          { title: '最近失败', dataIndex: 'lastFailureTime', render: formatTime },
          { title: '失败原因', dataIndex: 'failureReason', render: (value?: string) => value || '-' },
        ]}
      />
    </Card>
  )
}

export default DataSourceStatus
