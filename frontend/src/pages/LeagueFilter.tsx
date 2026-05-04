import { useEffect, useMemo, useState } from 'react'
import { Button, Card, Checkbox, Empty, Input, Space, Tag, Typography, message } from 'antd'
import { PlusOutlined, ReloadOutlined, SaveOutlined, SearchOutlined } from '@ant-design/icons'
import { apiClient } from '../services/api'

const { Text, Title } = Typography

type ApiResponse<T> = {
  code: number
  data: T
  msg?: string
}

type LeagueFilterData = {
  availableLeagues: string[]
  selectedLeagues: string[]
}

const LeagueFilter = () => {
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [availableLeagues, setAvailableLeagues] = useState<string[]>([])
  const [selectedLeagues, setSelectedLeagues] = useState<string[]>([])
  const [manualLeagueName, setManualLeagueName] = useState('')
  const [searchQuery, setSearchQuery] = useState('')

  const allLeagues = useMemo(() => {
    return Array.from(new Set([...availableLeagues, ...selectedLeagues]))
      .map((item) => item.trim())
      .filter(Boolean)
      .sort((a, b) => a.localeCompare(b, 'zh-CN'))
  }, [availableLeagues, selectedLeagues])

  const filteredLeagues = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    if (!keyword) {
      return allLeagues
    }
    return allLeagues.filter((league) => league.toLowerCase().includes(keyword))
  }, [allLeagues, searchQuery])

  const loadLeagues = async () => {
    setLoading(true)
    try {
      const response = await apiClient.post<ApiResponse<LeagueFilterData>>('/odds-monitor/leagues/list', {})
      if (response.data.code !== 0) {
        message.error(response.data.msg || '读取联赛失败')
        return
      }
      setAvailableLeagues(response.data.data.availableLeagues || [])
      setSelectedLeagues(response.data.data.selectedLeagues || [])
    } catch {
      message.error('读取联赛失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadLeagues()
  }, [])

  const addManualLeague = () => {
    const value = manualLeagueName.trim()
    if (!value) {
      return
    }
    setAvailableLeagues((current) => Array.from(new Set([...current, value])))
    setSelectedLeagues((current) => Array.from(new Set([...current, value])))
    setManualLeagueName('')
  }

  const saveLeagues = async () => {
    setSaving(true)
    try {
      const response = await apiClient.post<ApiResponse<LeagueFilterData>>('/odds-monitor/leagues/save', {
        selectedLeagues,
      })
      if (response.data.code !== 0) {
        message.error(response.data.msg || '保存联赛筛选失败')
        return
      }
      setAvailableLeagues(response.data.data.availableLeagues || [])
      setSelectedLeagues(response.data.data.selectedLeagues || [])
      message.success('联赛筛选已保存')
    } catch {
      message.error('保存联赛筛选失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div>
      <Space align="center" style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
        <Title level={2} style={{ margin: 0 }}>联赛筛选</Title>
        <Button icon={<ReloadOutlined />} onClick={loadLeagues} loading={loading}>刷新</Button>
      </Space>

      <Card
        title="关注联赛"
        extra={
          <Space wrap>
            <Input
              prefix={<SearchOutlined />}
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder="搜索联赛"
              allowClear
              style={{ width: 220 }}
            />
            <Input
              value={manualLeagueName}
              onChange={(event) => setManualLeagueName(event.target.value)}
              onPressEnter={addManualLeague}
              placeholder="手动增加联赛"
              style={{ width: 220 }}
            />
            <Button icon={<PlusOutlined />} onClick={addManualLeague}>增加</Button>
            <Button type="primary" icon={<SaveOutlined />} onClick={saveLeagues} loading={saving}>保存筛选</Button>
          </Space>
        }
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Text type="secondary">
            不勾选时显示全部比赛；勾选后，比赛监控页面只显示这些联赛。
          </Text>
          <Space wrap>
            <Tag color="blue">已选 {selectedLeagues.length}</Tag>
            <Tag>可选 {allLeagues.length}</Tag>
            {searchQuery.trim() && <Tag color="processing">搜索结果 {filteredLeagues.length}</Tag>}
          </Space>
          {filteredLeagues.length === 0 ? (
            <Empty description={searchQuery.trim() ? '没有找到联赛' : '暂无联赛，等待采集后刷新'} />
          ) : (
            <Checkbox.Group
              value={selectedLeagues}
              onChange={(values) => setSelectedLeagues(values.map(String))}
              style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 12 }}
            >
              {filteredLeagues.map((league) => (
                <Checkbox key={league} value={league}>
                  {league}
                </Checkbox>
              ))}
            </Checkbox.Group>
          )}
        </Space>
      </Card>
    </div>
  )
}

export default LeagueFilter
