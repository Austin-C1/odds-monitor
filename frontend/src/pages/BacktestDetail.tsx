import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Card, Descriptions, Button, Tag, Space, Table, message, Row, Col, Statistic, Spin } from 'antd'
import { ArrowLeftOutlined, ReloadOutlined, DeleteOutlined, StopOutlined, CopyOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { formatUSDC } from '../utils'
import { backtestService } from '../services/api'
import type { BacktestTaskDto, BacktestConfigDto, BacktestStatisticsDto, BacktestTradeDto } from '../types/backtest'
import { useMediaQuery } from 'react-responsive'
import BacktestChart from './BacktestChart'
import AddCopyTradingModal from './CopyTradingOrders/AddModal'

const BacktestDetail: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [task, setTask] = useState<BacktestTaskDto | null>(null)
  const [config, setConfig] = useState<BacktestConfigDto | null>(null)
  const [statistics, setStatistics] = useState<BacktestStatisticsDto | null>(null)
  const [trades, setTrades] = useState<BacktestTradeDto[]>([])
  const [allTrades, setAllTrades] = useState<BacktestTradeDto[]>([])  // 用于图表显示的所有交易数据
  const [tradesLoading, setTradesLoading] = useState(false)
  const [tradesTotal, setTradesTotal] = useState(0)
  const [tradesPage, setTradesPage] = useState(1)
  const [tradesSize] = useState(20)
  const [polling, setPolling] = useState<NodeJS.Timeout | null>(null)

  // 创建跟单配置 Modal
  const [addCopyTradingModalVisible, setAddCopyTradingModalVisible] = useState(false)
  const [preFilledConfig, setPreFilledConfig] = useState<any>(null)

  // 获取回测任务详情
  const fetchTaskDetail = async () => {
    setLoading(true)
    try {
      const response = await backtestService.detail({ id: parseInt(id!) })
      if (response.data.code === 0 && response.data.data) {
        setTask(response.data.data.task)
        setConfig(response.data.data.config)
        setStatistics(response.data.data.statistics)
      } else {
        message.error(response.data.msg || t('backtest.fetchTaskDetailFailed'))
      }
    } catch {
      message.error(t('backtest.fetchTaskDetailFailed'))
    } finally {
      setLoading(false)
    }
  }

  // 获取交易记录
  const fetchTrades = async (page: number) => {
    setTradesLoading(true)
    try {
      const response = await backtestService.trades({
        taskId: parseInt(id!),
        page,
        size: tradesSize
      })
      if (response.data.code === 0 && response.data.data) {
        setTrades(response.data.data.list)
        setTradesTotal(response.data.data.total)
      } else {
        message.error(response.data.msg || t('backtest.fetchTradesFailed'))
      }
    } catch {
      message.error(t('backtest.fetchTradesFailed'))
    } finally {
      setTradesLoading(false)
    }
  }

  // 获取所有交易记录（用于图表显示）
  const fetchAllTrades = async () => {
    try {
      const response = await backtestService.trades({
        taskId: parseInt(id!),
        page: 1,
        size: 10000  // 获取所有数据
      })
      if (response.data.code === 0 && response.data.data) {
        setAllTrades(response.data.data.list)
      }
    } catch {}
  }

  // 初始加载任务详情和交易记录
  useEffect(() => {
    fetchTaskDetail()
    fetchTrades(tradesPage)
    fetchAllTrades()  // 加载所有交易数据用于图表
  }, [id])

  // 根据任务状态控制轮询
  useEffect(() => {
    // 停止之前的轮询
    stopPolling()

    // 只有任务正在运行或待处理时才启动轮询
    if (task?.status === 'RUNNING' || task?.status === 'PENDING') {
      const timer = setInterval(() => {
        fetchTaskDetail()
      }, 3000) // 每3秒轮询一次
      setPolling(timer)
    }

    // 组件卸载或状态变化时清理定时器
    return () => {
      stopPolling()
    }
  }, [task?.status])

  const stopPolling = () => {
    if (polling) {
      clearInterval(polling)
      setPolling(null)
    }
  }

  // 返回
  const handleBack = () => {
    navigate('/backtest')
  }

  // 停止任务
  const handleStop = () => {
    if (!window.confirm(t('backtest.stopConfirm'))) return

    const stop = async () => {
      try {
        const response = await backtestService.stop({ id: parseInt(id!) })
        if (response.data.code === 0) {
          message.success(t('backtest.stopSuccess'))
          fetchTaskDetail()
          stopPolling()
        } else {
          message.error(response.data.msg || t('backtest.stopFailed'))
        }
      } catch {
        message.error(t('backtest.stopFailed'))
      }
    }
    stop()
  }

  // 删除任务
  const handleDelete = () => {
    if (!window.confirm(t('backtest.deleteConfirm'))) return

    const del = async () => {
      try {
        const response = await backtestService.delete({ id: parseInt(id!) })
        if (response.data.code === 0) {
          message.success(t('backtest.deleteSuccess'))
          stopPolling() // 停止轮询
          navigate('/backtest')
        } else {
          message.error(response.data.msg || t('backtest.deleteFailed'))
        }
      } catch {
        message.error(t('backtest.deleteFailed'))
      }
    }
    del()
  }

  // 刷新
  const handleRefresh = () => {
    fetchTaskDetail()
    fetchTrades(tradesPage)
  }

  // 一键创建跟单配置
  const handleCreateCopyTrading = () => {
    if (!task || !config) {
      return
    }

    // 预填充回测任务的配置参数（从 config 中获取）
    const preFilled = {
      leaderId: task.leaderId,
      copyMode: config.copyMode,
      copyRatio: config.copyMode === 'RATIO' ? parseFloat(config.copyRatio) * 100 : undefined,
      fixedAmount: config.copyMode === 'FIXED' ? config.fixedAmount : undefined,
      maxOrderSize: parseFloat(config.maxOrderSize),
      minOrderSize: parseFloat(config.minOrderSize),
      maxDailyLoss: parseFloat(config.maxDailyLoss),
      maxDailyOrders: config.maxDailyOrders,
      supportSell: config.supportSell,
      keywordFilterMode: config.keywordFilterMode || 'DISABLED',
      keywords: config.keywords || [],
      configName: `回测任务-${task.taskName}`
    }

    setPreFilledConfig(preFilled)
    setAddCopyTradingModalVisible(true)
  }

  // 状态标签颜色
  const getStatusColor = (status: string) => {
    switch (status) {
      case 'PENDING': return 'blue'
      case 'RUNNING': return 'processing'
      case 'COMPLETED': return 'success'
      case 'STOPPED': return 'warning'
      case 'FAILED': return 'error'
      default: return 'default'
    }
  }

  // 状态标签文本
  const getStatusText = (status: string) => {
    switch (status) {
      case 'PENDING': return t('backtest.statusPending')
      case 'RUNNING': return t('backtest.statusRunning')
      case 'COMPLETED': return t('backtest.statusCompleted')
      case 'STOPPED': return t('backtest.statusStopped')
      case 'FAILED': return t('backtest.statusFailed')
      default: return status
    }
  }

  const columns = [
    {
      title: t('backtest.tradeTime'),
      dataIndex: 'tradeTime',
      key: 'tradeTime',
      width: 180,
      render: (timestamp: number) => new Date(timestamp).toLocaleString()
    },
    {
      title: t('backtest.marketTitle'),
      dataIndex: 'marketTitle',
      key: 'marketTitle',
      width: 250,
      ellipsis: true
    },
    {
      title: t('backtest.side'),
      dataIndex: 'side',
      key: 'side',
      width: 100,
      render: (side: string) => (
        <Tag color={side === 'BUY' ? 'green' : side === 'SELL' ? 'orange' : 'blue'}>
          {side === 'BUY' ? t('backtest.sideBuy') : side === 'SELL' ? t('backtest.sideSell') : t('backtest.sideSettlement')}
        </Tag>
      )
    },
    {
      title: t('backtest.outcome'),
      dataIndex: 'outcome',
      key: 'outcome',
      width: 100
    },
    {
      title: t('backtest.quantity'),
      dataIndex: 'quantity',
      key: 'quantity',
      width: 100,
      render: (value: string) => parseFloat(value).toFixed(4)
    },
    {
      title: t('backtest.price'),
      dataIndex: 'price',
      key: 'price',
      width: 100,
      render: (value: string) => parseFloat(value).toFixed(4)
    },
    {
      title: t('backtest.amount') + ' (USDC)',
      dataIndex: 'amount',
      key: 'amount',
      width: 120,
      render: (value: string) => formatUSDC(value)
    },
    {
      title: t('backtest.balanceAfter') + ' (USDC)',
      dataIndex: 'balanceAfter',
      key: 'balanceAfter',
      width: 120,
      render: (value: string) => formatUSDC(value)
    },
    {
      title: t('backtest.leaderTradeId'),
      dataIndex: 'leaderTradeId',
      key: 'leaderTradeId',
      width: 150,
      ellipsis: true
    }
  ]

  if (!task) {
    return <div style={{ padding: 24, textAlign: 'center' }}><Spin /></div>
  }

  return (
    <div style={{ padding: 24 }}>
      <Card>
        {/* 头部操作栏 */}
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Space style={{ width: '100%', justifyContent: 'space-between', flexWrap: 'wrap' }}>
            <Space>
              <Button icon={<ArrowLeftOutlined />} onClick={handleBack} size={isMobile ? 'middle' : 'large'}>
                {t('common.back')}
              </Button>
              <Button icon={<ReloadOutlined />} onClick={handleRefresh} loading={loading} size={isMobile ? 'middle' : 'large'}>
                {t('common.refresh')}
              </Button>
            </Space>
            <Space>
              {task.status === 'COMPLETED' && (
                <Button type="primary" icon={<CopyOutlined />} onClick={handleCreateCopyTrading} size={isMobile ? 'middle' : 'large'}>
                  {t('backtest.createCopyTrading')}
                </Button>
              )}
              {(task.status === 'RUNNING' || task.status === 'PENDING') && (
                <Button danger icon={<StopOutlined />} onClick={handleStop} size={isMobile ? 'middle' : 'large'}>
                  {t('backtest.stop')}
                </Button>
              )}
              {(task.status === 'COMPLETED' || task.status === 'STOPPED' || task.status === 'FAILED') && (
                <Button danger icon={<DeleteOutlined />} onClick={handleDelete} size={isMobile ? 'middle' : 'large'}>
                  {t('common.delete')}
                </Button>
              )}
            </Space>
          </Space>

          {/* 任务基本信息 */}
          <Card title={t('backtest.taskDetail')} size="small">
            <Descriptions column={isMobile ? 1 : 2} bordered size="small">
              <Descriptions.Item label={t('backtest.taskName')}>{task.taskName}</Descriptions.Item>
              <Descriptions.Item label={t('backtest.leader')}>
                {task.leaderName || task.leaderAddress}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.initialBalance')}>
                {formatUSDC(task.initialBalance)} USDC
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.finalBalance')}>
                {task.finalBalance ? formatUSDC(task.finalBalance) + ' USDC' : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.profitAmount')}>
                <span style={{ color: task.profitAmount && parseFloat(task.profitAmount) >= 0 ? '#52c41a' : '#ff4d4f' }}>
                  {task.profitAmount ? formatUSDC(task.profitAmount) + ' USDC' : '-'}
                </span>
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.profitRate')}>
                <span style={{ color: task.profitRate && parseFloat(task.profitRate) >= 0 ? '#52c41a' : '#ff4d4f' }}>
                  {task.profitRate ? task.profitRate + '%' : '-'}
                </span>
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.backtestDays')}>
                {task.backtestDays} {t('common.day')}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.status')}>
                <Tag color={getStatusColor(task.status)}>{getStatusText(task.status)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.progress')}>
                {task.progress}%
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.totalTrades')}>
                {task.totalTrades}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.startTime')}>
                {new Date(task.startTime).toLocaleString()}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.endTime')}>
                {task.endTime ? new Date(task.endTime).toLocaleString() : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.createdAt')}>
                {new Date(task.createdAt).toLocaleString()}
              </Descriptions.Item>
            </Descriptions>
          </Card>

          {/* 统计信息 */}
          {statistics && (
            <Row gutter={16}>
              <Col xs={24} sm={12} md={12} lg={6}>
                <Card>
                  <Statistic
                    title={t('backtest.buyTrades')}
                    value={statistics.buyTrades}
                  />
                </Card>
              </Col>
              <Col xs={24} sm={12} md={12} lg={6}>
                <Card>
                  <Statistic
                    title={t('backtest.sellTrades')}
                    value={statistics.sellTrades}
                  />
                </Card>
              </Col>
              <Col xs={24} sm={12} md={12} lg={6}>
                <Card>
                  <Statistic
                    title={t('backtest.winTrades')}
                    value={statistics.winTrades}
                    valueStyle={{ color: '#52c41a' }}
                  />
                </Card>
              </Col>
              <Col xs={24} sm={12} md={12} lg={6}>
                <Card>
                  <Statistic
                    title={t('backtest.lossTrades')}
                    value={statistics.lossTrades}
                    valueStyle={{ color: '#ff4d4f' }}
                  />
                </Card>
              </Col>
              <Col xs={24} sm={12} md={12} lg={6}>
                <Card>
                  <Statistic
                    title={t('backtest.winRate')}
                    value={statistics.winRate}
                    suffix="%"
                    valueStyle={{ color: parseFloat(statistics.winRate) >= 50 ? '#52c41a' : '#ff4d4f' }}
                  />
                </Card>
              </Col>
              <Col xs={24} sm={12} md={12} lg={6}>
                <Card>
                  <Statistic
                    title={t('backtest.maxProfit')}
                    value={formatUSDC(statistics.maxProfit)}
                    valueStyle={{ color: '#52c41a' }}
                  />
                </Card>
              </Col>
              <Col xs={24} sm={12} md={12} lg={6}>
                <Card>
                  <Statistic
                    title={t('backtest.maxLoss')}
                    value={formatUSDC(statistics.maxLoss)}
                    valueStyle={{ color: '#ff4d4f' }}
                  />
                </Card>
              </Col>
              <Col xs={24} sm={12} md={12} lg={6}>
                <Card>
                  <Statistic
                    title={t('backtest.maxDrawdown')}
                    value={formatUSDC(statistics.maxDrawdown)}
                    valueStyle={{ color: '#ff4d4f' }}
                  />
                </Card>
              </Col>
              {statistics.avgHoldingTime && (
                <Col xs={24} sm={12} md={12} lg={6}>
                  <Card>
                    <Statistic
                      title={t('backtest.avgHoldingTime')}
                      value={(statistics.avgHoldingTime / 1000 / 60).toFixed(2)}
                      suffix=" min"
                    />
                  </Card>
                </Col>
              )}
            </Row>
          )}

          {/* 资金变化图表 */}
          {allTrades.length > 0 && (
            <Card title={t('backtest.balanceChart')}>
              <BacktestChart trades={allTrades} />
            </Card>
          )}

          {/* 交易记录 */}
          <Card title={t('backtest.tradeRecords')}>
            <Table
              columns={columns}
              dataSource={trades}
              rowKey="id"
              loading={tradesLoading}
              pagination={{
                current: tradesPage,
                pageSize: tradesSize,
                total: tradesTotal,
                showSizeChanger: false,
                showTotal: (total) => `${t('common.total')} ${total} ${t('common.items')}`,
                onChange: (newPage) => {
                  setTradesPage(newPage)
                  fetchTrades(newPage)
                }
              }}
              scroll={isMobile ? { x: 1200 } : { x: 1800 }}
            />
          </Card>
        </Space>
      </Card>

      {/* 创建跟单配置 Modal */}
      <AddCopyTradingModal
        open={addCopyTradingModalVisible}
        onClose={() => {
          setAddCopyTradingModalVisible(false)
          setPreFilledConfig(null)
        }}
        onSuccess={() => {
          message.success(t('backtest.createCopyTradingSuccess'))
          setAddCopyTradingModalVisible(false)
          setPreFilledConfig(null)
        }}
        preFilledConfig={preFilledConfig}
      />
    </div>
  )
}

export default BacktestDetail

