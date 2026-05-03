import { useEffect, useState } from 'react'
import { Badge, Button, Card, Col, Empty, Row, Space, Spin, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'

const { Title, Text } = Typography

interface ApiHealthItem {
  name: string
  url: string
  status: string
  message: string
  responseTime?: number
}

const ApiHealthStatus: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [items, setItems] = useState<ApiHealthItem[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    void refresh()
  }, [])

  const refresh = async () => {
    setLoading(true)
    try {
      const response = await apiService.proxyConfig.checkApiHealth()
      if (response.data.code === 0 && response.data.data) {
        setItems(response.data.data.apis || [])
      } else {
        setItems([])
      }
    } catch {
      setItems([])
    } finally {
      setLoading(false)
    }
  }

  const getStatusColor = (status: string) => {
    if (status === 'success') return '#52c41a'
    if (status === 'skipped') return '#999'
    return '#ff4d4f'
  }

  const getStatusBadge = (status: string): 'success' | 'default' | 'error' => {
    if (status === 'success') return 'success'
    if (status === 'skipped') return 'default'
    return 'error'
  }

  const getStatusText = (status: string) => {
    if (status === 'success') return t('apiHealthStatus.normal') || '正常'
    if (status === 'skipped') return t('apiHealthStatus.notConfigured') || '未配置'
    return t('apiHealthStatus.abnormal') || '异常'
  }

  const renderItem = (item: ApiHealthItem) => (
    <Card
      size="small"
      style={{
        borderLeft: `4px solid ${getStatusColor(item.status)}`,
        height: '100%',
      }}
      styles={{ body: { padding: isMobile ? '12px' : '16px' } }}
    >
      <Space direction="vertical" size="small" style={{ width: '100%' }}>
        <div
          style={{
            display: 'flex',
            alignItems: isMobile ? 'flex-start' : 'center',
            justifyContent: 'space-between',
            flexWrap: 'wrap',
            gap: '8px',
          }}
        >
          <Text strong style={{ fontSize: '14px' }}>
            {item.name}
          </Text>
          <Space>
            {item.responseTime !== undefined && item.responseTime !== null && (
              <Text type="secondary" style={{ fontSize: '12px' }}>
                <Text strong style={{ color: '#1890ff' }}>{item.responseTime}ms</Text>
              </Text>
            )}
            <Badge status={getStatusBadge(item.status)} text={getStatusText(item.status)} />
          </Space>
        </div>

        <Text type="secondary" style={{ fontSize: '12px', wordBreak: 'break-all' }}>
          {item.url}
        </Text>

        {item.message && item.message !== '连接成功' && (
          <Text
            type={item.status === 'success' ? 'success' : item.status === 'skipped' ? 'secondary' : 'danger'}
            style={{ fontSize: isMobile ? '12px' : '13px' }}
          >
            {item.message}
          </Text>
        )}
      </Space>
    </Card>
  )

  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>
          {t('apiHealthStatus.title') || 'API 健康状态'}
        </Title>
      </div>

      <Card
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()} loading={loading} size="small">
            {t('common.refresh') || '刷新'}
          </Button>
        }
      >
        <Spin spinning={loading}>
          {items.length === 0 ? (
            <Empty description={t('apiHealthStatus.noData') || '暂无健康检查结果'} />
          ) : (
            <Row gutter={[16, 16]}>
              {items.map((item) => (
                <Col key={item.name} xs={24} sm={12} md={12} lg={8} xl={6}>
                  {renderItem(item)}
                </Col>
              ))}
            </Row>
          )}
        </Spin>
      </Card>
    </div>
  )
}

export default ApiHealthStatus
