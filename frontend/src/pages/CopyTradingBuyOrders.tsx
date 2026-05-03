import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Card, Table, Button, Tag, Select, Input, message, Divider, Spin } from 'antd'
import { LeftOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { formatUSDC } from '../utils'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import type { BuyOrderInfo, OrderTrackingRequest, OrderTrackingListResponse } from '../types'

const { Option } = Select

const CopyTradingBuyOrdersPage: React.FC = () => {
  const { t } = useTranslation()
  const { copyTradingId } = useParams<{ copyTradingId: string }>()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [orders, setOrders] = useState<BuyOrderInfo[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [limit, setLimit] = useState(20)
  const [filters, setFilters] = useState<{
    marketId?: string
    side?: string
    status?: string
  }>({})
  
  useEffect(() => {
    if (copyTradingId) {
      fetchOrders()
    }
  }, [copyTradingId, page, limit, filters])
  
  const fetchOrders = async () => {
    if (!copyTradingId) return
    
    setLoading(true)
    try {
      const request: OrderTrackingRequest = {
        copyTradingId: parseInt(copyTradingId),
        type: 'buy',
        page,
        limit,
        ...filters
      }
      
      const response = await apiService.orderTracking.list(request)
      if (response.data.code === 0 && response.data.data) {
        const data = response.data.data as OrderTrackingListResponse
        setOrders((data.list || []) as BuyOrderInfo[])
        setTotal(data.total || 0)
      } else {
        message.error(response.data.msg || '获取买入订单列表失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取买入订单列表失败')
    } finally {
      setLoading(false)
    }
  }
  
  const getStatusTag = (status: string) => {
    const statusMap: Record<string, { color: string; text: string }> = {
      filled: { color: 'processing', text: '已完成' },
      partially_matched: { color: 'warning', text: '部分匹配' },
      fully_matched: { color: 'success', text: '完全匹配' }
    }
    const config = statusMap[status] || { color: 'default', text: status }
    return <Tag color={config.color}>{config.text}</Tag>
  }
  
  const columns = [
    {
      title: '订单ID',
      dataIndex: 'orderId',
      key: 'orderId',
      width: isMobile ? 100 : 150,
      render: (text: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? `${text.slice(0, 6)}...${text.slice(-4)}`
            : `${text.slice(0, 8)}...${text.slice(-6)}`
          }
        </span>
      )
    },
    {
      title: 'Leader 交易ID',
      dataIndex: 'leaderTradeId',
      key: 'leaderTradeId',
      width: isMobile ? 100 : 150,
      render: (text: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? `${text.slice(0, 6)}...${text.slice(-4)}`
            : `${text.slice(0, 8)}...${text.slice(-6)}`
          }
        </span>
      )
    },
    {
      title: '市场',
      dataIndex: 'marketId',
      key: 'marketId',
      width: isMobile ? 100 : 150,
      render: (text: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? `${text.slice(0, 6)}...${text.slice(-4)}`
            : `${text.slice(0, 8)}...${text.slice(-6)}`
          }
        </span>
      )
    },
    {
      title: '方向',
      dataIndex: 'side',
      key: 'side',
      width: isMobile ? 60 : 80,
      render: (side: string) => {
        // 将0/1转换为YES/NO
        const displaySide = side === '0' ? 'YES' : side === '1' ? 'NO' : side
        return <Tag style={{ fontSize: isMobile ? 11 : 12 }}>{displaySide}</Tag>
      }
    },
    {
      title: '买入数量',
      dataIndex: 'quantity',
      key: 'quantity',
      width: isMobile ? 80 : 100,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: '买入价格',
      dataIndex: 'price',
      key: 'price',
      width: isMobile ? 80 : 100,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: '买入金额',
      key: 'amount',
      width: isMobile ? 100 : 120,
      render: (_: any, record: BuyOrderInfo) => {
        const amount = (parseFloat(record.quantity) * parseFloat(record.price)).toString()
        return (
          <span style={{ fontSize: isMobile ? 12 : 14 }}>
            {isMobile ? formatUSDC(amount) : `${formatUSDC(amount)} USDC`}
          </span>
        )
      }
    },
    {
      title: '已匹配',
      dataIndex: 'matchedQuantity',
      key: 'matchedQuantity',
      width: isMobile ? 70 : 90,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: '剩余',
      dataIndex: 'remainingQuantity',
      key: 'remainingQuantity',
      width: isMobile ? 70 : 90,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: isMobile ? 80 : 100,
      render: (status: string) => getStatusTag(status)
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: isMobile ? 120 : 160,
      render: (timestamp: number) => (
        <span style={{ fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? new Date(timestamp).toLocaleDateString('zh-CN')
            : new Date(timestamp).toLocaleString('zh-CN')
          }
        </span>
      )
    }
  ]
  
  return (
    <div>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Button icon={<LeftOutlined />} onClick={() => navigate(-1)}>
              {t('common.back') || '返回'}
            </Button>
            <h2 style={{ margin: 0 }}>买入订单列表</h2>
          </div>
        </div>
        
        <div style={{ marginBottom: 16, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
          <Input
            placeholder="筛选市场ID"
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            value={filters.marketId}
            onChange={(e) => setFilters({ ...filters, marketId: e.target.value || undefined })}
          />
          
          <Select
            placeholder="筛选方向"
            allowClear
            style={{ width: isMobile ? '100%' : 150 }}
            value={filters.side}
            onChange={(value) => setFilters({ ...filters, side: value || undefined })}
          >
            <Option value="0">YES</Option>
            <Option value="1">NO</Option>
            <Option value="YES">YES</Option>
            <Option value="NO">NO</Option>
          </Select>
          
          <Select
            placeholder="筛选状态"
            allowClear
            style={{ width: isMobile ? '100%' : 150 }}
            value={filters.status}
            onChange={(value) => setFilters({ ...filters, status: value || undefined })}
          >
            <Option value="filled">已完成</Option>
            <Option value="partially_matched">部分匹配</Option>
            <Option value="fully_matched">完全匹配</Option>
          </Select>
          
          <Button onClick={fetchOrders}>查询</Button>
        </div>
        
        {isMobile ? (
          // 移动端卡片布局
          <div>
            {loading ? (
              <div style={{ textAlign: 'center', padding: '40px' }}>
                <Spin size="large" />
              </div>
            ) : orders.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
                暂无买入订单
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                {orders.map((order) => {
                  const date = new Date(order.createdAt)
                  const formattedDate = date.toLocaleString('zh-CN', {
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit'
                  })
                  const amount = (parseFloat(order.quantity) * parseFloat(order.price)).toString()
                  const displaySide = order.side === '0' ? 'YES' : order.side === '1' ? 'NO' : order.side
                  
                  return (
                    <Card
                      key={order.orderId}
                      style={{
                        borderRadius: '12px',
                        boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                        border: '1px solid #e8e8e8'
                      }}
                      styles={{ body: { padding: '16px' } }}
                    >
                      {/* 订单ID和状态 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ 
                          fontSize: '14px', 
                          fontWeight: 'bold', 
                          marginBottom: '8px',
                          fontFamily: 'monospace'
                        }}>
                          {order.orderId.slice(0, 8)}...{order.orderId.slice(-6)}
                        </div>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', alignItems: 'center' }}>
                          <Tag>{displaySide}</Tag>
                          {getStatusTag(order.status)}
                        </div>
                      </div>
                      
                      <Divider style={{ margin: '12px 0' }} />
                      
                      {/* 买入信息 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>买入信息</div>
                        <div style={{ fontSize: '14px', fontWeight: '500' }}>
                          数量: {formatUSDC(order.quantity)} | 价格: {formatUSDC(order.price)}
                        </div>
                        <div style={{ fontSize: '14px', fontWeight: '500', marginTop: '4px' }}>
                          金额: {formatUSDC(amount)} USDC
                        </div>
                      </div>
                      
                      {/* 匹配信息 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>匹配信息</div>
                        <div style={{ fontSize: '13px', color: '#333' }}>
                          已匹配: {formatUSDC(order.matchedQuantity)} | 剩余: {formatUSDC(order.remainingQuantity)}
                        </div>
                      </div>
                      
                      {/* Leader 交易ID */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>Leader 交易ID</div>
                        <div style={{ fontSize: '12px', color: '#999', fontFamily: 'monospace' }}>
                          {order.leaderTradeId.slice(0, 8)}...{order.leaderTradeId.slice(-6)}
                        </div>
                      </div>
                      
                      {/* 市场ID */}
                      <div style={{ marginBottom: '16px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>市场ID</div>
                        <div style={{ fontSize: '12px', color: '#999', fontFamily: 'monospace' }}>
                          {order.marketId.slice(0, 8)}...{order.marketId.slice(-6)}
                        </div>
                      </div>
                      
                      {/* 创建时间 */}
                      <div style={{ marginBottom: '16px' }}>
                        <div style={{ fontSize: '12px', color: '#999' }}>
                          创建时间: {formattedDate}
                        </div>
                      </div>
                    </Card>
                  )
                })}
              </div>
            )}
          </div>
        ) : (
          // 桌面端表格布局
          <Table
            columns={columns}
            dataSource={orders}
            rowKey="orderId"
            loading={loading}
            pagination={{
              current: page,
              pageSize: limit,
              total,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 条`,
              onChange: (newPage, newLimit) => {
                setPage(newPage)
                setLimit(newLimit)
              }
            }}
          />
        )}
      </Card>
    </div>
  )
}

export default CopyTradingBuyOrdersPage

