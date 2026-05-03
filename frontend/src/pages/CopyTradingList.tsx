import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  Button,
  Card,
  Empty,
  Popconfirm,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Tooltip,
  message,
} from 'antd'
import {
  BarChartOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  PlusOutlined,
  UnorderedListOutlined,
  UpOutlined,
} from '@ant-design/icons'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type { Account, CopyTrading, CopyTradingStatistics, Leader } from '../types'
import { formatUSDC } from '../utils'
import { loadCopyTradingStatisticsMap } from '../utils/copyTradingStatistics'
import CopyTradingOrdersModal from './CopyTradingOrders/index'
import StatisticsModal from './CopyTradingOrders/StatisticsModal'
import EditModal from './CopyTradingOrders/EditModal'
import AddModal from './CopyTradingOrders/AddModal'

type Filters = {
  group?: string
  accountId?: number
  leaderId?: number
  enabled?: boolean
}

type LeaderBucket = {
  leaderId: number
  leader?: Leader
  items: CopyTrading[]
}

type AccountBucket = {
  accountId: number
  account?: Account
  accountName: string
  walletAddress: string
  leaders: LeaderBucket[]
}

const getLeaderGroupLabel = (leader?: Leader) => {
  return leader?.customGroup?.trim() || '未分组'
}

const getNumberValue = (value?: string | number | null) => {
  const numeric = Number(value ?? 0)
  return Number.isFinite(numeric) ? numeric : 0
}

const sortGroupLabel = (left: string, right: string) => {
  if (left === '未分组') return -1
  if (right === '未分组') return 1
  return left.localeCompare(right)
}

const CopyTradingList: React.FC = () => {
  const [searchParams] = useSearchParams()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()

  const [copyTradings, setCopyTradings] = useState<CopyTrading[]>([])
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [statisticsMap, setStatisticsMap] = useState<Record<number, CopyTradingStatistics>>({})
  const [collapsedAccountIds, setCollapsedAccountIds] = useState<Record<number, boolean>>({})
  const [loading, setLoading] = useState(false)
  const [filters, setFilters] = useState<Filters>(() => {
    const leaderIdParam = Number(searchParams.get('leaderId'))
    const accountIdParam = Number(searchParams.get('accountId'))
    const enabledParam = searchParams.get('enabled')
    const groupParam = searchParams.get('group')?.trim()

    return {
      group: groupParam || undefined,
      accountId: Number.isFinite(accountIdParam) && accountIdParam > 0 ? accountIdParam : undefined,
      leaderId: Number.isFinite(leaderIdParam) && leaderIdParam > 0 ? leaderIdParam : undefined,
      enabled: enabledParam === 'true' ? true : enabledParam === 'false' ? false : undefined,
    }
  })

  const [ordersModalOpen, setOrdersModalOpen] = useState(false)
  const [ordersModalCopyTradingId, setOrdersModalCopyTradingId] = useState('')
  const [statisticsModalOpen, setStatisticsModalOpen] = useState(false)
  const [statisticsModalCopyTradingId, setStatisticsModalCopyTradingId] = useState('')
  const [editModalOpen, setEditModalOpen] = useState(false)
  const [editModalCopyTradingId, setEditModalCopyTradingId] = useState('')
  const [addModalOpen, setAddModalOpen] = useState(false)
  const [preFilledAccountId, setPreFilledAccountId] = useState<number | undefined>()
  const [preFilledLeaderId, setPreFilledLeaderId] = useState<number | undefined>()

  useEffect(() => {
    void fetchAccounts()
    void fetchLeaders()
  }, [fetchAccounts])

  useEffect(() => {
    void fetchCopyTradings()
  }, [filters.accountId, filters.enabled, filters.group, filters.leaderId])

  const fetchLeaders = async () => {
    try {
      const response = await apiService.leaders.list({})
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      }
    } catch (error: any) {
      message.error(error.message || '获取 Leader 列表失败')
    }
  }

  const fetchStatistics = async (list: CopyTrading[]) => {
    const nextMap = await loadCopyTradingStatisticsMap(list)
    setStatisticsMap(nextMap)
  }

  const fetchCopyTradings = async () => {
    setLoading(true)
    try {
      const response = await apiService.copyTrading.list({
        accountId: filters.accountId,
        leaderId: filters.leaderId,
        enabled: filters.enabled,
      })

      if (response.data.code !== 0 || !response.data.data) {
        message.error(response.data.msg || '获取跟单列表失败')
        return
      }

      const list = response.data.data.list || []
      setCopyTradings(list)
      await fetchStatistics(list)
    } catch (error: any) {
      message.error(error.message || '获取跟单列表失败')
    } finally {
      setLoading(false)
    }
  }

  const leaderById = useMemo(() => new Map(leaders.map((leader) => [leader.id, leader])), [leaders])
  const accountById = useMemo(() => new Map(accounts.map((account) => [account.id, account])), [accounts])

  const availableGroupOptions = useMemo(() => {
    return Array.from(new Set(leaders.map((leader) => getLeaderGroupLabel(leader)))).sort(sortGroupLabel)
  }, [leaders])

  const visibleCopyTradings = useMemo(() => {
    if (!filters.group) {
      return copyTradings
    }
    return copyTradings.filter((item) => getLeaderGroupLabel(leaderById.get(item.leaderId)) === filters.group)
  }, [copyTradings, filters.group, leaderById])

  const getConfigPnl = (copyTradingId: number) => getNumberValue(statisticsMap[copyTradingId]?.totalPnl)

  const accountGroups = useMemo<AccountBucket[]>(() => {
    const groupedByAccount = new Map<number, AccountBucket>()

    visibleCopyTradings.forEach((item) => {
      if (!groupedByAccount.has(item.accountId)) {
        const account = accountById.get(item.accountId)
        groupedByAccount.set(item.accountId, {
          accountId: item.accountId,
          account,
          accountName: item.accountName || account?.accountName || `账号 ${item.accountId}`,
          walletAddress: item.walletAddress || account?.walletAddress || '-',
          leaders: [],
        })
      }

      const accountBucket = groupedByAccount.get(item.accountId)!
      let leaderBucket = accountBucket.leaders.find((entry) => entry.leaderId === item.leaderId)
      if (!leaderBucket) {
        leaderBucket = {
          leaderId: item.leaderId,
          leader: leaderById.get(item.leaderId),
          items: [],
        }
        accountBucket.leaders.push(leaderBucket)
      }
      leaderBucket.items.push(item)
    })

    return Array.from(groupedByAccount.values()).sort((left, right) => left.accountName.localeCompare(right.accountName))
  }, [accountById, leaderById, visibleCopyTradings])

  const handleToggleStatus = async (copyTrading: CopyTrading) => {
    try {
      const response = await apiService.copyTrading.updateStatus({
        copyTradingId: copyTrading.id,
        enabled: !copyTrading.enabled,
      })

      if (response.data.code === 0) {
        message.success(copyTrading.enabled ? '跟单已暂停' : '跟单已开启')
        await fetchCopyTradings()
      } else {
        message.error(response.data.msg || '更新跟单状态失败')
      }
    } catch (error: any) {
      message.error(error.message || '更新跟单状态失败')
    }
  }

  const handleDelete = async (copyTradingId: number) => {
    try {
      const response = await apiService.copyTrading.delete({ copyTradingId })
      if (response.data.code === 0) {
        message.success('跟单配置已删除')
        await fetchCopyTradings()
      } else {
        message.error(response.data.msg || '删除跟单配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '删除跟单配置失败')
    }
  }

  const toggleAccountCollapsed = (accountId: number) => {
    setCollapsedAccountIds((current) => ({
      ...current,
      [accountId]: !current[accountId],
    }))
  }

  const addModalPreFilledConfig = useMemo(
    () => ({
      accountId: preFilledAccountId,
      leaderId: preFilledLeaderId,
    }),
    [preFilledAccountId, preFilledLeaderId],
  )

  const handleCloseAddModal = () => {
    setAddModalOpen(false)
    setPreFilledAccountId(undefined)
    setPreFilledLeaderId(undefined)
  }

  return (
    <div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 20,
          flexWrap: 'wrap',
          gap: 12,
        }}
      >
        <h2 style={{ margin: 0 }}>跟单配置</h2>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setPreFilledAccountId(undefined)
            setPreFilledLeaderId(undefined)
            setAddModalOpen(true)
          }}
        >
          新增跟单
        </Button>
      </div>

      <Card style={{ marginBottom: 16 }}>
        <Space wrap size={12}>
          <Select
            placeholder="筛选分组"
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            value={filters.group}
            onChange={(value) => setFilters((current) => ({ ...current, group: value || undefined }))}
            options={availableGroupOptions.map((group) => ({
              value: group,
              label: group,
            }))}
          />
          <Select
            placeholder="筛选钱包"
            allowClear
            style={{ width: isMobile ? '100%' : 220 }}
            value={filters.accountId}
            onChange={(value) => setFilters((current) => ({ ...current, accountId: value || undefined }))}
            options={accounts.map((account) => ({
              value: account.id,
              label: account.accountName || `账号 ${account.id}`,
            }))}
          />
          <Select
            placeholder="筛选 Leader"
            allowClear
            style={{ width: isMobile ? '100%' : 220 }}
            value={filters.leaderId}
            onChange={(value) => setFilters((current) => ({ ...current, leaderId: value || undefined }))}
            options={leaders.map((leader) => ({
              value: leader.id,
              label: leader.leaderName || `Leader ${leader.id}`,
            }))}
          />
          <Select
            placeholder="状态"
            allowClear
            style={{ width: isMobile ? '100%' : 160 }}
            value={filters.enabled}
            onChange={(value) => setFilters((current) => ({ ...current, enabled: value }))}
            options={[
              { value: true, label: '运行中' },
              { value: false, label: '已暂停' },
            ]}
          />
        </Space>
      </Card>

      {loading ? (
        <Card>
          <div style={{ textAlign: 'center', padding: '48px 0' }}>
            <Spin size="large" />
          </div>
        </Card>
      ) : accountGroups.length === 0 ? (
        <Card>
          <Empty description="暂无跟单配置" />
        </Card>
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {accountGroups.map((accountGroup) => {
            const enabledCount = accountGroup.leaders.reduce((sum, leaderBucket) => (
              sum + leaderBucket.items.filter((item) => item.enabled).length
            ), 0)
            const totalPnl = accountGroup.leaders.reduce((sum, leaderBucket) => (
              sum + leaderBucket.items.reduce((inner, item) => inner + getConfigPnl(item.id), 0)
            ), 0)
            const accountTags = Array.from(new Set(accountGroup.leaders.map((leaderBucket) => getLeaderGroupLabel(leaderBucket.leader)))).sort(sortGroupLabel)
            const isCollapsed = collapsedAccountIds[accountGroup.accountId] === true

            return (
              <Card key={accountGroup.accountId}>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                  <div style={{ flex: 1, minWidth: 260 }}>
                    <div style={{ fontSize: 24, fontWeight: 700, marginBottom: 6 }}>{accountGroup.accountName}</div>
                    <div style={{ color: '#666', marginBottom: 6 }}>{accountGroup.walletAddress}</div>
                    {accountGroup.account?.remark && (
                      <div style={{ color: '#666', marginBottom: 12 }}>{accountGroup.account.remark}</div>
                    )}
                    <Space wrap>
                      <Tag color="blue">已绑定 {accountGroup.leaders.length} 个 Leader</Tag>
                      {accountTags.map((tag) => (
                        <Tag key={`${accountGroup.accountId}-${tag}`}>{tag}</Tag>
                      ))}
                    </Space>
                  </div>

                  <Space wrap>
                    <Button
                      icon={isCollapsed ? <DownOutlined /> : <UpOutlined />}
                      onClick={() => toggleAccountCollapsed(accountGroup.accountId)}
                    >
                      {isCollapsed ? '展开账号' : '折叠账号'}
                    </Button>
                    <Button
                      type="primary"
                      icon={<PlusOutlined />}
                      onClick={() => {
                        setPreFilledAccountId(accountGroup.accountId)
                        setPreFilledLeaderId(undefined)
                        setAddModalOpen(true)
                      }}
                    >
                      给此账号新增
                    </Button>
                  </Space>
                </div>

                {!isCollapsed && (
                  <>
                    <div
                      style={{
                        display: 'grid',
                        gridTemplateColumns: isMobile ? '1fr 1fr' : 'repeat(2, minmax(0, 1fr))',
                        gap: 12,
                        marginTop: 16,
                      }}
                    >
                      <Card size="small">
                        <div style={{ fontSize: 12, color: '#666' }}>总收益</div>
                        <div style={{ fontSize: 22, fontWeight: 700, color: totalPnl >= 0 ? '#1677ff' : '#cf1322' }}>
                          {totalPnl >= 0 ? '+' : ''}
                          {formatUSDC(totalPnl.toString())} USDC
                        </div>
                      </Card>
                      <Card size="small">
                        <div style={{ fontSize: 12, color: '#666' }}>运行中</div>
                        <div style={{ fontSize: 22, fontWeight: 700 }}>{enabledCount}/{accountGroup.leaders.length}</div>
                      </Card>
                    </div>

                    <Space direction="vertical" size={12} style={{ width: '100%', marginTop: 16 }}>
                      {accountGroup.leaders.map((leaderBucket) => {
                        const enabledRelations = leaderBucket.items.filter((item) => item.enabled).length
                        const leaderCollapsedKey = `${accountGroup.accountId}-${leaderBucket.leaderId}`

                        return (
                          <Card key={leaderCollapsedKey} size="small">
                            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                              <div style={{ flex: 1, minWidth: 240 }}>
                                <div style={{ fontSize: 18, fontWeight: 700, marginBottom: 6 }}>
                                  {leaderBucket.leader?.leaderName || leaderBucket.items[0].leaderName || `Leader ${leaderBucket.leaderId}`}
                                </div>
                                <div style={{ color: '#666', marginBottom: 10 }}>
                                  {leaderBucket.leader?.leaderAddress || leaderBucket.items[0].leaderAddress}
                                </div>
                                <Space wrap>
                                  <Tag>{getLeaderGroupLabel(leaderBucket.leader)}</Tag>
                                  <Tag color="blue">已绑定 {enabledRelations}/{leaderBucket.items.length}</Tag>
                                </Space>
                              </div>

                              <Space wrap>
                                <Button
                                  icon={<PlusOutlined />}
                                  onClick={() => {
                                    setPreFilledAccountId(accountGroup.accountId)
                                    setPreFilledLeaderId(leaderBucket.leaderId)
                                    setAddModalOpen(true)
                                  }}
                                >
                                  为此 Leader 新增
                                </Button>
                              </Space>
                            </div>

                            <Space direction="vertical" size={12} style={{ width: '100%', marginTop: 16 }}>
                              {leaderBucket.items.map((item) => {
                                const stats = statisticsMap[item.id]
                                const pnl = getConfigPnl(item.id)

                                return (
                                  <Card key={item.id} size="small">
                                    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                                      <div style={{ flex: 1, minWidth: 260 }}>
                                        <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 8 }}>
                                          {item.configName || `配置 ${item.id}`}
                                        </div>
                                        <Space wrap style={{ marginBottom: 8 }}>
                                          <Tag color={item.supportSell ? 'orange' : 'default'}>
                                            {item.supportSell ? '支持跟随卖出' : '仅跟买入'}
                                          </Tag>
                                        </Space>
                                        <div style={{ color: '#666', marginBottom: 4 }}>
                                          总收益 {pnl >= 0 ? '+' : ''}
                                          {formatUSDC((stats?.totalPnl || '0').toString())} USDC
                                        </div>
                                        <div style={{ color: '#999', fontSize: 12 }}>{item.walletAddress}</div>
                                      </div>

                                      <div style={{ minWidth: isMobile ? '100%' : 220, display: 'flex', flexDirection: 'column', gap: 12 }}>
                                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                          <div>
                                            <div style={{ fontSize: 12, color: '#666' }}>当前状态</div>
                                            <div style={{ fontSize: 18, fontWeight: 700 }}>{item.enabled ? '运行中' : '已暂停'}</div>
                                          </div>
                                          <Switch
                                            checked={item.enabled}
                                            onChange={() => handleToggleStatus(item)}
                                            checkedChildren="开"
                                            unCheckedChildren="关"
                                          />
                                        </div>

                                        <Space wrap>
                                          <Tooltip title="统计">
                                            <Button
                                              icon={<BarChartOutlined />}
                                              onClick={() => {
                                                setStatisticsModalCopyTradingId(String(item.id))
                                                setStatisticsModalOpen(true)
                                              }}
                                            />
                                          </Tooltip>
                                          <Tooltip title="订单">
                                            <Button
                                              icon={<UnorderedListOutlined />}
                                              onClick={() => {
                                                setOrdersModalCopyTradingId(String(item.id))
                                                setOrdersModalOpen(true)
                                              }}
                                            />
                                          </Tooltip>
                                          <Tooltip title="编辑">
                                            <Button
                                              icon={<EditOutlined />}
                                              onClick={() => {
                                                setEditModalCopyTradingId(String(item.id))
                                                setEditModalOpen(true)
                                              }}
                                            />
                                          </Tooltip>
                                          <Popconfirm
                                            title="确认删除这条跟单配置？"
                                            onConfirm={() => handleDelete(item.id)}
                                            okText="删除"
                                            cancelText="取消"
                                          >
                                            <Button danger icon={<DeleteOutlined />} />
                                          </Popconfirm>
                                        </Space>
                                      </div>
                                    </div>
                                  </Card>
                                )
                              })}
                            </Space>
                          </Card>
                        )
                      })}
                    </Space>
                  </>
                )}
              </Card>
            )
          })}
        </Space>
      )}

      <CopyTradingOrdersModal
        open={ordersModalOpen}
        copyTradingId={ordersModalCopyTradingId}
        onClose={() => setOrdersModalOpen(false)}
      />
      <StatisticsModal
        open={statisticsModalOpen}
        copyTradingId={statisticsModalCopyTradingId}
        onClose={() => setStatisticsModalOpen(false)}
      />
      <EditModal
        open={editModalOpen}
        copyTradingId={editModalCopyTradingId}
        onClose={() => setEditModalOpen(false)}
        onSuccess={() => {
          setEditModalOpen(false)
          void fetchCopyTradings()
        }}
      />
      <AddModal
        open={addModalOpen}
        onClose={handleCloseAddModal}
        onSuccess={() => {
          handleCloseAddModal()
          void fetchCopyTradings()
        }}
        preFilledConfig={addModalPreFilledConfig}
      />
    </div>
  )
}

export default CopyTradingList
