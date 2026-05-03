import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Card, message, Spin, Typography } from 'antd'
import { BarChartOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { setToken } from '../utils'
import { useMediaQuery } from 'react-responsive'

const { Title, Text } = Typography

const Login: React.FC = () => {
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)

  const localLogin = async () => {
    setLoading(true)
    try {
      const response = await apiService.auth.localLogin()
      if (response.data.code === 0 && response.data.data) {
        setToken(response.data.data.token)
        navigate('/odds-monitor', { replace: true })
        return
      }
      message.error(response.data.msg || '免密登录失败')
    } catch (error: any) {
      const errorMsg = error.response?.data?.msg || error.message || '免密登录失败'
      message.error(errorMsg)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    localLogin()
  }, [])

  return (
    <div style={{
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      minHeight: '100vh',
      padding: isMobile ? '20px' : '40px',
      background: '#0e1218'
    }}>
      <Card style={{ width: isMobile ? '100%' : 380, textAlign: 'center' }}>
        <BarChartOutlined style={{ fontSize: 36, color: '#1677ff', marginBottom: 16 }} />
        <Title level={2} style={{ marginBottom: 8 }}>全平台赔率监控</Title>
        <Text type="secondary">本机免密进入</Text>
        <div style={{ marginTop: 28 }}>
          {loading ? (
            <Spin />
          ) : (
            <Button type="primary" block size="large" onClick={localLogin}>
              进入系统
            </Button>
          )}
        </div>
      </Card>
    </div>
  )
}

export default Login
