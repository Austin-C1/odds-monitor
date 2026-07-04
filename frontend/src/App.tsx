import { lazy, Suspense, useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { ConfigProvider, Spin } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import Layout from './components/Layout'
import CrownBettingBackgroundRunner from './components/CrownBettingBackgroundRunner'
import { apiService } from './services/api'
import { hasToken } from './utils'

const Login = lazy(() => import('./pages/Login'))
const ResetPassword = lazy(() => import('./pages/ResetPassword'))
const OddsMonitor = lazy(() => import('./pages/OddsMonitor'))
const DefaultTracking = lazy(() => import('./pages/DefaultTracking'))
const CrownLeagueFilter = lazy(() => import('./pages/CrownLeagueFilter'))
const CrownBetting = lazy(() => import('./pages/CrownBetting'))
const BettingHistory = lazy(() => import('./pages/BettingHistory'))
const DataSourceSettings = lazy(() => import('./pages/DataSourceSettings'))
const DataSourceStatus = lazy(() => import('./pages/DataSourceStatus'))
const AlertRecords = lazy(() => import('./pages/AlertRecords'))
const NotificationSettingsPage = lazy(() => import('./pages/NotificationSettingsPage'))
const RuntimeLogs = lazy(() => import('./pages/RuntimeLogs'))
const SystemUpdate = lazy(() => import('./pages/SystemUpdate'))

const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const location = useLocation()
  const isAuthPage = location.pathname === '/login' || location.pathname === '/reset-password'

  if (isAuthPage) {
    return <>{children}</>
  }

  if (!hasToken()) {
    return <Navigate to="/login" replace />
  }

  return (
    <Layout>
      <CrownBettingBackgroundRunner />
      {children}
    </Layout>
  )
}

const RouteFallback: React.FC = () => (
  <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '50vh' }}>
    <Spin size="large" />
  </div>
)

const LazyRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <Suspense fallback={<RouteFallback />}>{children}</Suspense>
)

function App() {
  const [isFirstUse, setIsFirstUse] = useState<boolean | null>(null)
  const [checking, setChecking] = useState(true)

  useEffect(() => {
    const checkFirstUse = async () => {
      try {
        const response = await apiService.auth.checkFirstUse()
        if (response.data.code === 0 && response.data.data) {
          setIsFirstUse(response.data.data.isFirstUse)
        }
      } catch (error) {
        console.error('check first use failed', error)
        setIsFirstUse(false)
      } finally {
        setChecking(false)
      }
    }

    checkFirstUse()
  }, [])

  if (checking) {
    return (
      <ConfigProvider locale={zhCN}>
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh' }}>
          <Spin size="large" />
        </div>
      </ConfigProvider>
    )
  }

  if (isFirstUse === true) {
    return (
      <ConfigProvider locale={zhCN}>
        <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <Routes>
            <Route path="/reset-password" element={<LazyRoute><ResetPassword /></LazyRoute>} />
            <Route path="*" element={<Navigate to="/reset-password" replace />} />
          </Routes>
        </BrowserRouter>
      </ConfigProvider>
    )
  }

  return (
    <ConfigProvider locale={zhCN}>
      <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          <Route path="/login" element={<LazyRoute><Login /></LazyRoute>} />
          <Route path="/reset-password" element={<LazyRoute><ResetPassword /></LazyRoute>} />
          <Route path="/" element={<ProtectedRoute><Navigate to="/odds-monitor" replace /></ProtectedRoute>} />
          <Route path="/odds-monitor" element={<ProtectedRoute><LazyRoute><OddsMonitor /></LazyRoute></ProtectedRoute>} />
          <Route path="/default-tracking" element={<ProtectedRoute><LazyRoute><DefaultTracking /></LazyRoute></ProtectedRoute>} />
          <Route path="/crown-league-filter" element={<ProtectedRoute><LazyRoute><CrownLeagueFilter /></LazyRoute></ProtectedRoute>} />
          <Route path="/crown-betting" element={<ProtectedRoute><LazyRoute><CrownBetting /></LazyRoute></ProtectedRoute>} />
          <Route path="/betting-history" element={<ProtectedRoute><LazyRoute><BettingHistory /></LazyRoute></ProtectedRoute>} />
          <Route path="/data-sources/settings" element={<ProtectedRoute><LazyRoute><DataSourceSettings /></LazyRoute></ProtectedRoute>} />
          <Route path="/data-sources/status" element={<ProtectedRoute><LazyRoute><DataSourceStatus /></LazyRoute></ProtectedRoute>} />
          <Route path="/alerts" element={<ProtectedRoute><LazyRoute><AlertRecords /></LazyRoute></ProtectedRoute>} />
          <Route path="/system-settings/notification" element={<ProtectedRoute><LazyRoute><NotificationSettingsPage /></LazyRoute></ProtectedRoute>} />
          <Route path="/runtime-logs" element={<ProtectedRoute><LazyRoute><RuntimeLogs /></LazyRoute></ProtectedRoute>} />
          <Route path="/system-settings/update" element={<ProtectedRoute><LazyRoute><SystemUpdate /></LazyRoute></ProtectedRoute>} />
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </BrowserRouter>
    </ConfigProvider>
  )
}

export default App
