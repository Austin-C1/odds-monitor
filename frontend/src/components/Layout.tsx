import { ReactNode, useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Layout as AntLayout, Button, Drawer, Menu, Modal, Tag } from 'antd'
import type { MenuProps } from 'antd'
import {
  AlertOutlined,
  BarChartOutlined,
  CloudUploadOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  FilterOutlined,
  LogoutOutlined,
  MenuOutlined,
  NotificationOutlined,
  SearchOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { useMediaQuery } from 'react-responsive'
import { removeToken, getVersionInfo, getVersionText } from '../utils'
import { wsManager } from '../services/websocket'

const { Header, Content, Sider } = AntLayout

interface LayoutProps {
  children: ReactNode
}

const menuItems: MenuProps['items'] = [
  { key: '/odds-monitor', icon: <BarChartOutlined />, label: '比赛监控' },
  { key: '/league-filter', icon: <FilterOutlined />, label: '联赛筛选' },
  { key: '/data-sources/settings', icon: <SettingOutlined />, label: '数据源设置' },
  { key: '/data-sources/status', icon: <CloudServerOutlined />, label: '数据源状态' },
  { key: '/alerts', icon: <AlertOutlined />, label: '告警记录' },
  { key: '/system-settings/notification', icon: <NotificationOutlined />, label: '告警通知' },
  { key: '/polymarket-query', icon: <SearchOutlined />, label: 'Polymarket 查询' },
  { key: '/runtime-logs', icon: <DatabaseOutlined />, label: '运行日志' },
  { key: '/system-settings/update', icon: <CloudUploadOutlined />, label: '系统更新' },
  { type: 'divider' },
  { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' },
]

const Layout: React.FC<LayoutProps> = ({ children }) => {
  const navigate = useNavigate()
  const location = useLocation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [selectedKeys, setSelectedKeys] = useState<string[]>([location.pathname])

  useEffect(() => {
    setSelectedKeys([location.pathname])
  }, [location.pathname])

  const handleLogout = () => {
    removeToken()
    wsManager.disconnect()
    navigate('/login', { replace: true })
  }

  const handleMenuClick = ({ key }: { key: string }) => {
    if (key === 'logout') {
      Modal.confirm({
        title: '确认退出',
        content: '退出后需要重新登录。',
        okText: '确认',
        cancelText: '取消',
        onOk: handleLogout,
      })
      return
    }

    navigate(key)
    if (isMobile) {
      setMobileMenuOpen(false)
    }
  }

  const brand = (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
      <BarChartOutlined style={{ color: '#52c41a', fontSize: 20 }} />
      <span style={{ color: '#fff', fontSize: 18, fontWeight: 700, whiteSpace: 'nowrap' }}>
        全平台赔率监控
      </span>
      <Tag
        color="success"
        style={{
          margin: 0,
          background: 'transparent',
          borderRadius: 4,
          lineHeight: 1.5,
          maxWidth: 72,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {getVersionInfo().gitTag || `v${getVersionText()}`}
      </Tag>
    </div>
  )

  const menu = (
    <Menu
      mode="inline"
      selectedKeys={selectedKeys}
      items={menuItems}
      onClick={handleMenuClick}
      style={{ borderRight: 0, height: '100%' }}
    />
  )

  if (isMobile) {
    return (
      <AntLayout style={{ minHeight: '100vh' }}>
        <Header style={{ background: '#001529', padding: '0 16px', display: 'flex', justifyContent: 'space-between' }}>
          {brand}
          <Button type="text" icon={<MenuOutlined />} style={{ color: '#fff' }} onClick={() => setMobileMenuOpen(true)} />
        </Header>
        <Content style={{ padding: 12, background: '#f4f6f8', minHeight: 'calc(100vh - 64px)' }}>
          {children}
        </Content>
        <Drawer title="导航" placement="left" open={mobileMenuOpen} onClose={() => setMobileMenuOpen(false)} styles={{ body: { padding: 0 } }}>
          {menu}
        </Drawer>
      </AntLayout>
    )
  }

  return (
    <AntLayout style={{ height: '100vh', overflow: 'hidden' }}>
      <Sider width={220} style={{ background: '#001529', height: '100vh', position: 'fixed', left: 0, top: 0 }}>
        <div style={{ padding: 16, borderBottom: '1px solid rgba(255,255,255,0.1)' }}>
          {brand}
        </div>
        <div style={{ height: 'calc(100vh - 72px)', overflowY: 'auto' }}>{menu}</div>
      </Sider>
      <AntLayout style={{ marginLeft: 220, height: '100vh' }}>
        <Content style={{ padding: 24, background: '#f4f6f8', height: '100vh', overflowY: 'auto' }}>
          {children}
        </Content>
      </AntLayout>
    </AntLayout>
  )
}

export default Layout
