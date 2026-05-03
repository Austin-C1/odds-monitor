import { useState } from 'react'
import { Card, Form, Input, Button, message, Typography, Alert, Progress } from 'antd'
import { LockOutlined, KeyOutlined, UserOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'

const { Title } = Typography

/**
 * 计算密码强度
 * @param password 密码
 * @returns 强度等级 0-4 (0: 弱, 1: 较弱, 2: 中等, 3: 强, 4: 很强)
 */
const getPasswordStrength = (password: string): number => {
  if (!password) return 0
  if (password.length < 6) return 0
  
  let strength = 0
  // 长度加分
  if (password.length >= 6) strength += 1
  if (password.length >= 8) strength += 1
  if (password.length >= 12) strength += 1
  
  // 字符类型加分
  if (/[a-z]/.test(password)) strength += 0.5
  if (/[A-Z]/.test(password)) strength += 0.5
  if (/\d/.test(password)) strength += 0.5
  if (/[^a-zA-Z0-9]/.test(password)) strength += 0.5
  
  return Math.min(4, Math.floor(strength))
}

const ResetPassword: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [passwordStrength, setPasswordStrength] = useState(0)
  const [form] = Form.useForm()

  /**
   * 获取密码强度文本和颜色
   */
  const getPasswordStrengthInfo = (strength: number): { text: string; color: string; percent: number } => {
    switch (strength) {
      case 0:
        return { text: t('resetPassword.weak') || '弱', color: '#ff4d4f', percent: 25 }
      case 1:
        return { text: t('resetPassword.fair') || '较弱', color: '#ff7a45', percent: 50 }
      case 2:
        return { text: t('resetPassword.medium') || '中等', color: '#faad14', percent: 75 }
      case 3:
        return { text: t('resetPassword.strong') || '强', color: '#52c41a', percent: 100 }
      case 4:
        return { text: t('resetPassword.veryStrong') || '很强', color: '#52c41a', percent: 100 }
      default:
        return { text: t('resetPassword.weak') || '弱', color: '#ff4d4f', percent: 0 }
    }
  }

  const handleReset = async (values: {
    resetKey: string
    username: string
    newPassword: string
    confirmPassword: string
  }) => {
    if (values.newPassword !== values.confirmPassword) {
      message.error(t('resetPassword.passwordMismatch') || '两次输入的密码不一致')
      return
    }

    setLoading(true)
    try {
      const response = await apiService.auth.resetPassword({
        resetKey: values.resetKey,
        username: values.username,
        newPassword: values.newPassword
      })
      if (response.data.code === 0) {
        message.success(t('resetPassword.success') || '密码重置成功', 1)
        // 使用 window.location.href 强制跳转到登录页，确保跳转成功
        setTimeout(() => {
          window.location.href = '/login'
        }, 500)
      } else {
        message.error(response.data.msg || t('resetPassword.failed') || '密码重置失败')
      }
    } catch (error: any) {
      console.error('密码重置失败:', error)
      const errorMsg = error.response?.data?.msg || error.message || t('resetPassword.failed') || '密码重置失败'
      message.error(errorMsg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      minHeight: '100vh',
      padding: isMobile ? '20px' : '40px',
      background: '#f0f2f5'
    }}>
      <Card
        style={{
          width: isMobile ? '100%' : '500px',
          boxShadow: '0 2px 8px rgba(0,0,0,0.1)'
        }}
      >
        <Title level={2} style={{ textAlign: 'center', marginBottom: '16px' }}>
          {t('resetPassword.title') || '重置密码'}
        </Title>
        <Alert
          message={t('resetPassword.firstUse') || '首次使用系统'}
          description={t('resetPassword.firstUseDesc') || '请使用管理员提供的重置密钥设置初始密码'}
          type="info"
          showIcon
          style={{ marginBottom: '24px' }}
        />
        <Form
          form={form}
          onFinish={handleReset}
          layout="vertical"
          size={isMobile ? 'large' : 'middle'}
        >
          <Form.Item
            name="resetKey"
            label={t('resetPassword.resetKey') || '重置密钥'}
            rules={[
              { required: true, message: t('resetPassword.resetKeyRequired') || '请输入重置密钥' }
            ]}
          >
            <Input
              prefix={<KeyOutlined />}
              placeholder={t('resetPassword.resetKeyPlaceholder') || '请输入重置密钥'}
            />
          </Form.Item>
          <Form.Item
            name="username"
            label={t('resetPassword.username') || '用户名'}
            rules={[
              { required: true, message: t('resetPassword.usernameRequired') || '请输入用户名' }
            ]}
          >
            <Input
              prefix={<UserOutlined />}
              placeholder={t('resetPassword.usernamePlaceholder') || '请输入用户名'}
            />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label={t('resetPassword.newPassword') || '新密码'}
            rules={[
              { required: true, message: t('resetPassword.newPasswordRequired') || '请输入新密码' },
              { min: 6, message: t('resetPassword.passwordMinLength') || '密码至少6位' }
            ]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder={t('resetPassword.passwordPlaceholder') || '至少6位'}
              onChange={(e) => {
                const strength = getPasswordStrength(e.target.value)
                setPasswordStrength(strength)
              }}
            />
          </Form.Item>
          {passwordStrength > 0 && (
            <Form.Item>
              <div style={{ marginTop: '-16px', marginBottom: '16px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                  <span style={{ fontSize: '12px', color: '#666' }}>{t('resetPassword.passwordStrength') || '密码强度'}：</span>
                  <span style={{ 
                    fontSize: '12px', 
                    fontWeight: 'bold',
                    color: getPasswordStrengthInfo(passwordStrength).color
                  }}>
                    {getPasswordStrengthInfo(passwordStrength).text}
                  </span>
                </div>
                <Progress
                  percent={getPasswordStrengthInfo(passwordStrength).percent}
                  strokeColor={getPasswordStrengthInfo(passwordStrength).color}
                  showInfo={false}
                  size="small"
                />
              </div>
            </Form.Item>
          )}
          <Form.Item
            name="confirmPassword"
            label={t('resetPassword.confirmPassword') || '确认密码'}
            dependencies={['newPassword']}
            rules={[
              { required: true, message: t('resetPassword.confirmPasswordRequired') || '请确认密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve()
                  }
                  return Promise.reject(new Error(t('resetPassword.passwordMismatch') || '两次输入的密码不一致'))
                }
              })
            ]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder={t('resetPassword.confirmPasswordPlaceholder') || '请再次输入密码'}
            />
          </Form.Item>
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              block
              loading={loading}
              size={isMobile ? 'large' : 'middle'}
            >
              {t('resetPassword.submit') || '重置密码'}
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default ResetPassword

