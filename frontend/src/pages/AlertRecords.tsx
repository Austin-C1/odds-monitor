import { useEffect, useState } from 'react'
import { Card, Table, Tag, message } from 'antd'
import { apiClient } from '../services/api'
import { PageShell } from './PageShell'

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

const getErrorMessage = (error: unknown) => {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return '告警记录读取失败'
}

const AlertRecords = () => {
  const [rows, setRows] = useState<AlertRecord[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const loadRows = async () => {
      setLoading(true)
      try {
        const response = await apiClient.post<ApiResponse<AlertRecord[]>>('/odds-monitor/alerts/list', {})
        const payload = response.data
        if (payload.code !== 0 || !Array.isArray(payload.data)) {
          throw new Error(payload.msg || '告警记录读取失败')
        }
        setRows(payload.data)
      } catch (error) {
        message.error(getErrorMessage(error))
      } finally {
        setLoading(false)
      }
    }

    void loadRows()
  }, [])

  return (
    <PageShell
      title="告警记录"
      description="只看赔率变化、盘口变化、平台差异和数据源异常记录。"
      actions={<Tag color="blue">odds_change</Tag>}
      className="alert-records-page"
    >
      <Card>
      <Table
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
    </PageShell>
  )
}

export default AlertRecords
