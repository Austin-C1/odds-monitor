import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Badge,
  Button,
  Card,
  Descriptions,
  Divider,
  Empty,
  List,
  Modal,
  Popconfirm,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import {
  CopyOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  EyeOutlined,
  GlobalOutlined,
  LineChartOutlined,
  PlusOutlined,
  ReloadOutlined,
  UpOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import type { Leader, LeaderBalanceResponse, PositionDto } from '../types'
import { formatUSDC } from '../utils'
import { loadCopyTradingStatisticsMap } from '../utils/copyTradingStatistics'

const { Text } = Typography

type LeaderBalanceSummary = {
  total: string
  available: string
  position: string
  positionsCount: number
}

type LeaderPerformanceSummary = {
  totalPnl: string
}

type LeaderGroup = {
  groupName: string
  leaders: Leader[]
}

const EMPTY_BALANCE_SUMMARY: LeaderBalanceSummary = {
  total: '-',
  available: '-',
  position: '-',
  positionsCount: 0,
}

const EMPTY_PERFORMANCE_SUMMARY: LeaderPerformanceSummary = {
  totalPnl: '0',
}

const getNumberValue = (value?: string | number | null) => {
  const numeric = Number(value ?? 0)
  return Number.isFinite(numeric) ? numeric : 0
}

const buildBalanceSummary = (balance: LeaderBalanceResponse): LeaderBalanceSummary => ({
  total: balance.totalBalance || '0',
  available: balance.availableBalance || '0',
  position: balance.positionBalance || '0',
  positionsCount: balance.positions?.length || 0,
})

const getLeaderGroupLabel = (leader: Leader, ungroupedLabel: string) => leader.customGroup?.trim() || ungroupedLabel

const sortGroupLabel = (left: string, right: string, ungroupedLabel: string) => {
  if (left === ungroupedLabel) return -1
  if (right === ungroupedLabel) return 1
  return left.localeCompare(right)
}

const formatSignedUsdc = (value?: string | number | null) => {
  const numeric = getNumberValue(value)
  return `${numeric >= 0 ? '+' : ''}${formatUSDC(numeric.toString())} USDC`
}

const getMetricColor = (value?: string | number | null) => {
  return getNumberValue(value) >= 0 ? '#52c41a' : '#ff4d4f'
}

const LeaderList: React.FC = () => {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const ungroupedLabel = t('leaderList.ungrouped', { defaultValue: '未分组' })

  const [leaders, setLeaders] = useState<Leader[]>([])
  const [loading, setLoading] = useState(false)
  const [balanceMap, setBalanceMap] = useState<Record<number, LeaderBalanceSummary>>({})
  const [performanceMap, setPerformanceMap] = useState<Record<number, LeaderPerformanceSummary>>({})
  const [collapsedGroups, setCollapsedGroups] = useState<Record<string, boolean>>({})
  const balanceMapRef = useRef<Record<number, LeaderBalanceSummary>>({})
  const performanceMapRef = useRef<Record<number, LeaderPerformanceSummary>>({})

  const [detailModalVisible, setDetailModalVisible] = useState(false)
  const [detailLeader, setDetailLeader] = useState<Leader | null>(null)
  const [detailBalance, setDetailBalance] = useState<LeaderBalanceResponse | null>(null)
  const [detailPerformance, setDetailPerformance] = useState<LeaderPerformanceSummary | null>(null)
  const [detailBalanceLoading, setDetailBalanceLoading] = useState(false)
  const [detailPerformanceLoading, setDetailPerformanceLoading] = useState(false)

  useEffect(() => {
    void fetchLeaders()
  }, [])

  useEffect(() => {
    balanceMapRef.current = balanceMap
  }, [balanceMap])

  useEffect(() => {
    performanceMapRef.current = performanceMap
  }, [performanceMap])

  const resolveLeaderPerformance = async (leaderIds: number[]): Promise<Record<number, LeaderPerformanceSummary>> => {
    const uniqueLeaderIds = Array.from(new Set(leaderIds.filter((id) => id > 0)))
    if (uniqueLeaderIds.length === 0) {
      return {}
    }

    const copyTradingResult = await apiService.copyTrading.list({})

    const copyTradings = copyTradingResult.data.code === 0 && copyTradingResult.data.data
      ? copyTradingResult.data.data.list.filter((item) => uniqueLeaderIds.includes(item.leaderId))
      : []

    const statisticsMap = await loadCopyTradingStatisticsMap(copyTradings)

    const totalPnlByLeader = copyTradings.reduce<Record<number, number>>((acc, item) => {
      const pnl = getNumberValue(statisticsMap[item.id]?.totalPnl)
      acc[item.leaderId] = (acc[item.leaderId] || 0) + pnl
      return acc
    }, {})

    const next: Record<number, LeaderPerformanceSummary> = {}
    uniqueLeaderIds.forEach((leaderId) => {
      next[leaderId] = {
        totalPnl: (totalPnlByLeader[leaderId] ?? 0).toString(),
      }
    })

    return next
  }

  const mergeLeaderPerformance = async (leaderIds: number[]) => {
    const next = await resolveLeaderPerformance(leaderIds)
    if (Object.keys(next).length === 0) {
      return next
    }

    setPerformanceMap((prev) => {
      const merged = { ...prev, ...next }
      performanceMapRef.current = merged
      return merged
    })
    return next
  }

  useEffect(() => {
    const pendingLeaders = leaders.filter((leader) => !balanceMapRef.current[leader.id])
    if (pendingLeaders.length === 0) {
      return
    }

    const loadBalances = async () => {
      const results = await Promise.allSettled(
        pendingLeaders.map(async (leader) => {
          const balanceData = await apiService.leaders.balance({ leaderId: leader.id })
          const balance = balanceData.data.data
          if (balanceData.data.code !== 0 || !balance) {
            throw new Error(balanceData.data.msg || t('leaderDetail.fetchBalanceFailed'))
          }

          return {
            leaderId: leader.id,
            balance: buildBalanceSummary(balance),
          }
        }),
      )

      setBalanceMap((prev) => {
        const next = { ...prev }
        pendingLeaders.forEach((leader, index) => {
          const result = results[index]
          next[leader.id] = result.status === 'fulfilled' ? result.value.balance : EMPTY_BALANCE_SUMMARY
        })
        balanceMapRef.current = next
        return next
      })
    }

    void loadBalances()
  }, [leaders, t])

  useEffect(() => {
    const pendingLeaderIds = leaders
      .map((leader) => leader.id)
      .filter((leaderId) => !performanceMapRef.current[leaderId])

    if (pendingLeaderIds.length === 0) {
      return
    }

    const loadPerformance = async () => {
      try {
        const next = await resolveLeaderPerformance(pendingLeaderIds)
        setPerformanceMap((prev) => {
          const merged = { ...prev }
          pendingLeaderIds.forEach((leaderId) => {
            merged[leaderId] = next[leaderId] || EMPTY_PERFORMANCE_SUMMARY
          })
          performanceMapRef.current = merged
          return merged
        })
      } catch {
        setPerformanceMap((prev) => {
          const merged = { ...prev }
          pendingLeaderIds.forEach((leaderId) => {
            merged[leaderId] = EMPTY_PERFORMANCE_SUMMARY
          })
          performanceMapRef.current = merged
          return merged
        })
      }
    }

    void loadPerformance()
  }, [leaders])

  const groupedLeaders = useMemo<LeaderGroup[]>(() => {
    const groups = new Map<string, Leader[]>()
    leaders.forEach((leader) => {
      const groupName = getLeaderGroupLabel(leader, ungroupedLabel)
      if (!groups.has(groupName)) {
        groups.set(groupName, [])
      }
      groups.get(groupName)!.push(leader)
    })

    return Array.from(groups.entries())
      .sort(([left], [right]) => sortGroupLabel(left, right, ungroupedLabel))
      .map(([groupName, groupLeaders]) => ({
        groupName,
        leaders: groupLeaders,
      }))
  }, [leaders, ungroupedLabel])

  useEffect(() => {
    setCollapsedGroups((current) => (
      groupedLeaders.reduce<Record<string, boolean>>((next, group) => {
        next[group.groupName] = current[group.groupName] ?? true
        return next
      }, {})
    ))
  }, [groupedLeaders])

  const fetchLeaders = async () => {
    setLoading(true)
    try {
      const response = await apiService.leaders.list()
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      } else {
        message.error(response.data.msg || t('leaderList.fetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderList.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }

  const handleDelete = async (leaderId: number) => {
    try {
      const response = await apiService.leaders.delete({ leaderId })
      if (response.data.code === 0) {
        message.success(t('leaderList.deleteSuccess'))
        await fetchLeaders()
      } else {
        message.error(response.data.msg || t('leaderList.deleteFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderList.deleteFailed'))
    }
  }

  const toggleGroupCollapsed = (groupName: string) => {
    setCollapsedGroups((current) => ({
      ...current,
      [groupName]: current[groupName] === false,
    }))
  }

  const handleShowDetail = async (leader: Leader) => {
    setDetailModalVisible(true)
    setDetailLeader(leader)
    setDetailBalance(null)
    setDetailPerformance(performanceMapRef.current[leader.id] ?? null)
    setDetailBalanceLoading(true)
    setDetailPerformanceLoading(true)

    const [detailResult, balanceResult, performanceResult] = await Promise.allSettled([
      apiService.leaders.detail({ leaderId: leader.id }),
      apiService.leaders.balance({ leaderId: leader.id }),
      mergeLeaderPerformance([leader.id]),
    ])

    if (detailResult.status === 'fulfilled' && detailResult.value.data.code === 0 && detailResult.value.data.data) {
      setDetailLeader(detailResult.value.data.data)
    } else if (detailResult.status === 'rejected') {
      message.error(detailResult.reason?.message || t('leaderList.fetchFailed'))
    }

    if (balanceResult.status === 'fulfilled' && balanceResult.value.data.code === 0 && balanceResult.value.data.data) {
      const nextBalance = balanceResult.value.data.data
      setDetailBalance(nextBalance)
      setBalanceMap((prev) => {
        const merged = { ...prev, [leader.id]: buildBalanceSummary(nextBalance) }
        balanceMapRef.current = merged
        return merged
      })
    } else if (balanceResult.status === 'rejected') {
      message.error(balanceResult.reason?.message || t('leaderDetail.fetchBalanceFailed'))
    }

    if (performanceResult.status === 'fulfilled') {
      setDetailPerformance(performanceResult.value[leader.id] || EMPTY_PERFORMANCE_SUMMARY)
    } else {
      setDetailPerformance(EMPTY_PERFORMANCE_SUMMARY)
    }

    setDetailBalanceLoading(false)
    setDetailPerformanceLoading(false)
  }

  const handleRefreshDetailMetrics = async () => {
    if (!detailLeader) return

    setDetailBalanceLoading(true)
    setDetailPerformanceLoading(true)

    const [balanceResult, performanceResult] = await Promise.allSettled([
      apiService.leaders.balance({ leaderId: detailLeader.id }),
      mergeLeaderPerformance([detailLeader.id]),
    ])

    if (balanceResult.status === 'fulfilled' && balanceResult.value.data.code === 0 && balanceResult.value.data.data) {
      const nextBalance = balanceResult.value.data.data
      setDetailBalance(nextBalance)
      setBalanceMap((prev) => {
        const merged = { ...prev, [detailLeader.id]: buildBalanceSummary(nextBalance) }
        balanceMapRef.current = merged
        return merged
      })
    } else {
      message.error(t('leaderDetail.fetchBalanceFailed'))
    }

    if (performanceResult.status === 'fulfilled') {
      setDetailPerformance(performanceResult.value[detailLeader.id] || EMPTY_PERFORMANCE_SUMMARY)
    }

    setDetailBalanceLoading(false)
    setDetailPerformanceLoading(false)
    message.success(t('leaderDetail.refresh'))
  }

  const formatTimestamp = (timestamp: number) => {
    const date = new Date(timestamp)
    return date.toLocaleString(i18n.language || 'zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  const getPositionColumns = () => [
    {
      title: t('leaderDetail.market'),
      dataIndex: 'title',
      key: 'title',
      render: (title: string) => {
        if (!title) return <Text type="secondary">-</Text>
        const displayText = isMobile && title.length > 20 ? `${title.slice(0, 20)}...` : title
        return <Text style={{ fontSize: isMobile ? 12 : 13 }}>{displayText}</Text>
      },
    },
    {
      title: t('leaderDetail.side'),
      dataIndex: 'side',
      key: 'side',
      render: (side: string) => {
        const color = side === 'YES' ? 'green' : 'red'
        return <Tag color={color}>{side}</Tag>
      },
    },
    {
      title: t('leaderDetail.quantity'),
      dataIndex: 'quantity',
      key: 'quantity',
      render: (quantity: string) => formatUSDC(quantity),
    },
    {
      title: t('leaderDetail.avgPrice'),
      dataIndex: 'avgPrice',
      key: 'avgPrice',
      render: (avgPrice: string) => formatUSDC(avgPrice),
    },
    {
      title: t('leaderDetail.currentValue'),
      dataIndex: 'currentValue',
      key: 'currentValue',
      render: (currentValue: string) => formatUSDC(currentValue),
    },
    {
      title: t('leaderDetail.pnl'),
      dataIndex: 'pnl',
      key: 'pnl',
      render: (pnl: string | undefined) => {
        if (!pnl || pnl === '0') {
          return <Text type="secondary">-</Text>
        }
        return <Text style={{ color: getMetricColor(pnl) }}>{formatUSDC(pnl)}</Text>
      },
    },
  ]

  const renderMetricItem = (
    itemKey: string,
    label: React.ReactNode,
    value: React.ReactNode,
    color?: string,
    minWidth = 110,
  ) => (
    <div key={itemKey} style={{ minWidth }}>
      <Text type="secondary" style={{ fontSize: 12 }}>{label}</Text>
      <div style={{ color, fontWeight: 600 }}>{value}</div>
    </div>
  )

  const renderAssetOverview = (record: Leader) => {
    const balance = balanceMap[record.id]
    const performance = performanceMap[record.id]

    if (!balance || !performance) {
      return <Spin size="small" />
    }

    return (
      <Space direction="vertical" size={8}>
        <span style={{ display: 'none' }}>
          {t('leaderList.currentPositions')}
          {t('leaderDetail.availableBalance')}
          {t('leaderDetail.positionBalance')}
        </span>
        <div>
          <Text type="secondary" style={{ fontSize: 12 }}>{t('leaderDetail.totalBalance')}</Text>
          <div>
            <Text style={{ color: '#52c41a', fontWeight: 700 }}>
              {balance.total === '-' ? '-' : `${formatUSDC(balance.total)} USDC`}
            </Text>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
          {renderMetricItem(
            'currentPositions',
            <>{t('leaderList.currentPositions', { defaultValue: '仓位' })}</>,
            balance.positionsCount,
          )}
          {renderMetricItem(
            'availableBalance',
            <>{t('leaderDetail.availableBalance', { defaultValue: '现金' })}</>,
            balance.available === '-' ? '-' : `${formatUSDC(balance.available)} USDC`,
          )}
          {renderMetricItem(
            'positionBalance',
            <>{t('leaderDetail.positionBalance', { defaultValue: '持仓总值' })}</>,
            balance.position === '-' ? '-' : `${formatUSDC(balance.position)} USDC`,
          )}
          {renderMetricItem(
            'totalPnl',
            t('leaderList.totalPnl', { defaultValue: '总盈利' }),
            formatSignedUsdc(performance.totalPnl),
            getMetricColor(performance.totalPnl),
          )}
        </div>
      </Space>
    )
  }

  const columns = [
    {
      title: t('leaderList.leaderName'),
      dataIndex: 'leaderName',
      key: 'leaderName',
      render: (text: string, record: Leader) => (
        <Space direction="vertical" size={0}>
          <Text strong>{text || `Leader ${record.id}`}</Text>
          <Text type="secondary" style={{ fontFamily: 'monospace' }}>{record.leaderAddress}</Text>
        </Space>
      ),
    },
    {
      title: t('leaderList.customGroup', { defaultValue: '自定义分组' }),
      key: 'customGroup',
      render: (_: unknown, record: Leader) => <Tag>{getLeaderGroupLabel(record, ungroupedLabel)}</Tag>,
    },
    {
      title: t('leaderList.remark'),
      dataIndex: 'remark',
      key: 'remark',
      ellipsis: true,
      render: (remark: string | undefined) => (
        remark ? <Text ellipsis={{ tooltip: remark }}>{remark}</Text> : <Text type="secondary">-</Text>
      ),
    },
    {
      title: t('leaderList.assetOverview'),
      key: 'balance',
      render: (_: unknown, record: Leader) => renderAssetOverview(record),
    },
    {
      title: t('common.actions'),
      key: 'action',
      render: (_: unknown, record: Leader) => (
        <Space size={4}>
          <Tooltip title={t('common.viewDetail')}>
            <Button icon={<EyeOutlined />} onClick={() => handleShowDetail(record)} />
          </Tooltip>
          {record.website && (
            <Tooltip title={t('leaderList.openWebsite')}>
              <Button icon={<GlobalOutlined />} onClick={() => window.open(record.website, '_blank', 'noopener,noreferrer')} />
            </Tooltip>
          )}
          <Tooltip title={t('common.edit')}>
            <Button icon={<EditOutlined />} onClick={() => navigate(`/leaders/edit?id=${record.id}`)} />
          </Tooltip>
          <Tooltip title={`${t('leaderList.viewCopyTradings')} (${record.copyTradingCount})`}>
            <Badge count={record.copyTradingCount} size="small">
              <Button icon={<CopyOutlined />} onClick={() => record.copyTradingCount > 0 && navigate(`/copy-trading?leaderId=${record.id}`)} />
            </Badge>
          </Tooltip>
          <Tooltip title={`${t('leaderList.viewBacktests')} (${record.backtestCount})`}>
            <Badge count={record.backtestCount} size="small">
              <Button icon={<LineChartOutlined />} onClick={() => record.backtestCount > 0 && navigate(`/backtest?leaderId=${record.id}`)} />
            </Badge>
          </Tooltip>
          <Popconfirm
            title={t('leaderList.deleteConfirm')}
            description={record.copyTradingCount > 0 ? t('leaderList.deleteConfirmDesc', { count: record.copyTradingCount }) : undefined}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ]

  const renderMobileLeaderCard = (leader: Leader) => {
    const balance = balanceMap[leader.id]
    const performance = performanceMap[leader.id]

    return (
      <Card key={leader.id} size="small">
        <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 4 }}>{leader.leaderName || `Leader ${leader.id}`}</div>
        <div style={{ color: '#666', fontFamily: 'monospace', marginBottom: 8 }}>{leader.leaderAddress}</div>
        <Space wrap style={{ marginBottom: 8 }}>
          <Tag>{getLeaderGroupLabel(leader, ungroupedLabel)}</Tag>
          {leader.remark && <Tag color="gold">{leader.remark}</Tag>}
        </Space>
        {balance && performance ? (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 8 }}>
            <Tag>{`${t('leaderList.currentPositions', { defaultValue: '仓位' })} ${balance.positionsCount}`}</Tag>
            <Tag>{`${t('leaderDetail.availableBalance', { defaultValue: '现金' })} ${balance.available === '-' ? '-' : `${formatUSDC(balance.available)} USDC`}`}</Tag>
            <Tag>{`${t('leaderDetail.positionBalance', { defaultValue: '持仓总值' })} ${balance.position === '-' ? '-' : `${formatUSDC(balance.position)} USDC`}`}</Tag>
            <Tag color={getNumberValue(performance.totalPnl) >= 0 ? 'green' : 'red'}>
              {`${t('leaderList.totalPnl', { defaultValue: '总盈利' })} ${formatSignedUsdc(performance.totalPnl)}`}
            </Tag>
          </div>
        ) : (
          <Spin size="small" />
        )}
        <Space wrap>
          <Button icon={<EyeOutlined />} onClick={() => handleShowDetail(leader)} />
          {leader.website && <Button icon={<GlobalOutlined />} onClick={() => window.open(leader.website, '_blank', 'noopener,noreferrer')} />}
          <Button icon={<EditOutlined />} onClick={() => navigate(`/leaders/edit?id=${leader.id}`)} />
          <Button icon={<CopyOutlined />} onClick={() => leader.copyTradingCount > 0 && navigate(`/copy-trading?leaderId=${leader.id}`)} />
          <Button icon={<LineChartOutlined />} onClick={() => leader.backtestCount > 0 && navigate(`/backtest?leaderId=${leader.id}`)} />
          <Popconfirm
            title={t('leaderList.deleteConfirm')}
            description={leader.copyTradingCount > 0 ? t('leaderList.deleteConfirmDesc', { count: leader.copyTradingCount }) : undefined}
            onConfirm={() => handleDelete(leader.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      </Card>
    )
  }

  const renderDetailSummary = () => {
    if (detailBalanceLoading && detailPerformanceLoading && !detailBalance && !detailPerformance) {
      return (
        <div style={{ textAlign: 'center', padding: '40px' }}>
          <Spin />
        </div>
      )
    }

    if (!detailBalance && !detailPerformance) {
      return <Empty description={t('leaderDetail.fetchBalanceFailed')} />
    }

    const balance = detailBalance ? buildBalanceSummary(detailBalance) : EMPTY_BALANCE_SUMMARY
    const performance = detailPerformance || EMPTY_PERFORMANCE_SUMMARY

    return (
      <Descriptions bordered column={isMobile ? 1 : 3}>
        <Descriptions.Item label={t('leaderDetail.totalBalance')}>
          {balance.total === '-' ? '-' : `${formatUSDC(balance.total)} USDC`}
        </Descriptions.Item>
        <Descriptions.Item label={t('leaderDetail.positionCount', { defaultValue: '仓位' })}>
          {balance.positionsCount}
        </Descriptions.Item>
        <Descriptions.Item label={t('leaderDetail.availableBalance', { defaultValue: '现金' })}>
          {balance.available === '-' ? '-' : `${formatUSDC(balance.available)} USDC`}
        </Descriptions.Item>
        <Descriptions.Item label={t('leaderDetail.positionBalance', { defaultValue: '持仓总值' })}>
          {balance.position === '-' ? '-' : `${formatUSDC(balance.position)} USDC`}
        </Descriptions.Item>
        <Descriptions.Item label={t('leaderDetail.totalPnl', { defaultValue: '总盈利' })}>
          <Text style={{ color: getMetricColor(performance.totalPnl) }}>{formatSignedUsdc(performance.totalPnl)}</Text>
        </Descriptions.Item>
      </Descriptions>
    )
  }

  const renderDetailPositions = () => {
    if (detailBalanceLoading && !detailBalance) {
      return null
    }

    const positions = detailBalance?.positions || []

    return (
      <>
        <Divider />
        <div style={{ marginBottom: 16 }}>
          <Space>
            <span style={{ fontSize: 16, fontWeight: 'bold' }}>{t('leaderDetail.positions')}</span>
            <Tag color="blue">{positions.length}</Tag>
          </Space>
        </div>

        {positions.length > 0 ? (
          <Table<PositionDto>
            dataSource={positions}
            columns={getPositionColumns()}
            rowKey={(record) => `${record.marketId}-${record.side}-${record.avgPrice}-${record.quantity}`}
            pagination={{ pageSize: 10, showSizeChanger: !isMobile }}
            scroll={{ x: isMobile ? 800 : 'auto' }}
            size={isMobile ? 'small' : 'middle'}
          />
        ) : (
          <Empty description={t('leaderDetail.noPositions')} />
        )}
      </>
    )
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20, flexWrap: 'wrap', gap: 12 }}>
        <h2 style={{ margin: 0, fontSize: isMobile ? '20px' : '24px' }}>{t('leaderList.title')}</h2>
        <Tooltip title={t('leaderList.addLeader')}>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/leaders/add')}
            size={isMobile ? 'middle' : 'large'}
            style={{ borderRadius: 8, height: isMobile ? 40 : 48, fontSize: isMobile ? 14 : 16 }}
          />
        </Tooltip>
      </div>

      {loading ? (
        <Card>
          <div style={{ textAlign: 'center', padding: '48px 0' }}>
            <Spin size="large" />
          </div>
        </Card>
      ) : groupedLeaders.length === 0 ? (
        <Card>
          <Empty description={t('leaderList.noData')} />
        </Card>
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {groupedLeaders.map((group) => {
            const isGroupCollapsed = collapsedGroups[group.groupName] !== false
            const toggleLabel = isGroupCollapsed
              ? t('leaderList.expandGroup', { defaultValue: '展开分组' })
              : t('leaderList.collapseGroup', { defaultValue: '收起分组' })

            return (
              <Card
                key={group.groupName}
                styles={{ body: isGroupCollapsed ? { display: 'none' } : undefined }}
                title={(
                  <Space>
                  <span>{group.groupName}</span>
                  <Tag>{`${group.leaders.length} 个 Leader`}</Tag>
                  </Space>
                )}
                extra={(
                  <Button
                    type="text"
                    size="small"
                    aria-expanded={!isGroupCollapsed}
                    aria-label={toggleLabel}
                    icon={isGroupCollapsed ? <DownOutlined /> : <UpOutlined />}
                    onClick={() => toggleGroupCollapsed(group.groupName)}
                  >
                    {toggleLabel}
                  </Button>
                )}
              >
                {!isGroupCollapsed && (isMobile ? (
                  <List dataSource={group.leaders} renderItem={(leader) => renderMobileLeaderCard(leader)} />
                ) : (
                  <Table
                    dataSource={group.leaders}
                    columns={columns}
                    rowKey="id"
                    pagination={false}
                    scroll={{ x: 1350 }}
                  />
                ))}
              </Card>
            )
          })}
        </Space>
      )}

      <Modal
        title={(
          <Space>
            <WalletOutlined />
            <span>{t('leaderDetail.title')}</span>
          </Space>
        )}
        open={detailModalVisible}
        onCancel={() => setDetailModalVisible(false)}
        footer={[
          <Button key="close" onClick={() => setDetailModalVisible(false)}>{t('common.close')}</Button>,
        ]}
        width={isMobile ? '95%' : 1040}
        style={{ top: 20 }}
      >
        {!detailLeader ? (
          <div style={{ textAlign: 'center', padding: '40px' }}>
            <Spin size="large" />
          </div>
        ) : (
          <>
            <Descriptions bordered column={isMobile ? 1 : 2}>
              <Descriptions.Item label={t('leaderDetail.leaderName')}>
                {detailLeader.leaderName || `Leader ${detailLeader.id}`}
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderList.customGroup', { defaultValue: '自定义分组' })}>
                {getLeaderGroupLabel(detailLeader, ungroupedLabel)}
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderDetail.leaderAddress')} span={isMobile ? 1 : 2}>
                <span style={{ fontFamily: 'monospace' }}>{detailLeader.leaderAddress}</span>
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderDetail.copyTradingCount')}>
                <Tag color="cyan">{detailLeader.copyTradingCount || 0}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderDetail.remark')}>
                {detailLeader.remark || <Text type="secondary">-</Text>}
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderDetail.updatedAt')}>
                {formatTimestamp(detailLeader.updatedAt)}
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderDetail.website')}>
                {detailLeader.website ? (
                  <Button
                    type="link"
                    icon={<GlobalOutlined />}
                    onClick={() => window.open(detailLeader.website, '_blank', 'noopener,noreferrer')}
                    style={{ padding: 0 }}
                  >
                    {t('leaderDetail.openWebsite')}
                  </Button>
                ) : <Text type="secondary">-</Text>}
              </Descriptions.Item>
            </Descriptions>

            <Divider />

            <Space style={{ marginBottom: 16 }}>
              <WalletOutlined />
              <span style={{ fontSize: 16, fontWeight: 'bold' }}>{t('leaderDetail.balanceInfo')}</span>
              <Button
                type="text"
                size="small"
                icon={<ReloadOutlined />}
                onClick={handleRefreshDetailMetrics}
                loading={detailBalanceLoading || detailPerformanceLoading}
              >
                {t('leaderDetail.refresh')}
              </Button>
            </Space>

            {renderDetailSummary()}
            {renderDetailPositions()}
          </>
        )}
      </Modal>
    </div>
  )
}

export default LeaderList
