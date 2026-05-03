import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Form, Button, Switch, Input, InputNumber, message, Typography, Space, Alert, Select } from 'antd'
import { SaveOutlined, CheckCircleOutlined, ReloadOutlined, GlobalOutlined, NotificationOutlined, LinkOutlined, RightOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import type { ProxySettingsFormValues } from '../types'

const { Title, Text, Paragraph } = Typography

interface ProxyConfig {
  id?: number
  type: string
  enabled: boolean
  host?: string
  port?: number
  username?: string
  subscriptionUrl?: string
  lastSubscriptionUpdate?: number
  createdAt: number
  updatedAt: number
}

interface ProxyCheckResponse {
  success: boolean
  message: string
  responseTime?: number
  latency?: number
}

const SystemSettings: React.FC = () => {
  const { t, i18n: i18nInstance } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const navigate = useNavigate()

  const [languageForm] = Form.useForm()
  const [currentLang, setCurrentLang] = useState<string>('auto')

  const [proxyForm] = Form.useForm<ProxySettingsFormValues>()
  const [proxyLoading, setProxyLoading] = useState(false)
  const [proxyChecking, setProxyChecking] = useState(false)
  const [proxyCheckResult, setProxyCheckResult] = useState<ProxyCheckResponse | null>(null)
  const [currentProxyConfig, setCurrentProxyConfig] = useState<ProxyConfig | null>(null)

  useEffect(() => {
    const savedLanguage = localStorage.getItem('i18n_language') || 'auto'
    setCurrentLang(savedLanguage)
    languageForm.setFieldsValue({ language: savedLanguage })
    fetchProxyConfig()
  }, [])

  const detectSystemLanguage = (): string => {
    const systemLanguage = navigator.language || navigator.languages?.[0] || 'en'
    const lang = systemLanguage.toLowerCase()
    if (lang.startsWith('zh')) {
      if (lang.includes('tw') || lang.includes('hk') || lang.includes('mo')) {
        return 'zh-TW'
      }
      return 'zh-CN'
    }
    return 'en'
  }

  const handleLanguageSubmit = async (values: { language: string }) => {
    try {
      let actualLang = values.language
      if (values.language === 'auto') {
        actualLang = detectSystemLanguage()
        localStorage.setItem('i18n_language', 'auto')
      } else {
        localStorage.setItem('i18n_language', values.language)
      }

      setCurrentLang(values.language)
      await i18nInstance.changeLanguage(actualLang)
      message.success(t('languageSettings.changeSuccess') || '语言设置已保存')
    } catch {
      message.error(t('languageSettings.changeFailed') || '语言设置保存失败')
    }
  }

  const fetchProxyConfig = async () => {
    try {
      const response = await apiService.proxyConfig.get()
      if (response.data.code === 0) {
        const data = response.data.data
        setCurrentProxyConfig(data)
        if (data) {
          proxyForm.setFieldsValue({
            enabled: data.enabled,
            host: data.host || '',
            port: data.port || undefined,
            username: data.username || '',
            password: ''
          })
        } else {
          proxyForm.resetFields()
        }
      } else {
        message.error(response.data.msg || '获取代理配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取代理配置失败')
    }
  }

  const handleProxySubmit = async (values: ProxySettingsFormValues) => {
    setProxyLoading(true)
    try {
      const host = values.host?.trim()
      const port = values.port
      if (!host || port === undefined) {
        message.error('请完整填写代理地址和端口')
        return
      }

      const response = await apiService.proxyConfig.saveHttp({
        enabled: values.enabled ?? false,
        host,
        port,
        username: values.username?.trim() || undefined,
        password: values.password || undefined
      })
      if (response.data.code === 0) {
        message.success('代理配置已保存')
        fetchProxyConfig()
        setProxyCheckResult(null)
      } else {
        message.error(response.data.msg || '保存代理配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '保存代理配置失败')
    } finally {
      setProxyLoading(false)
    }
  }

  const handleProxyCheck = async () => {
    setProxyChecking(true)
    setProxyCheckResult(null)
    try {
      const response = await apiService.proxyConfig.check()
      if (response.data.code === 0 && response.data.data) {
        const result = response.data.data
        setProxyCheckResult(result)
        if (result.success) {
          message.success(`代理检查成功：${result.message}`)
        } else {
          message.warning(`代理检查失败：${result.message}`)
        }
      } else {
        message.error(response.data.msg || '代理检查失败')
      }
    } catch (error: any) {
      message.error(error.message || '代理检查失败')
    } finally {
      setProxyChecking(false)
    }
  }

  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('systemSettings.title') || '通用设置'}</Title>
      </div>

      <Card
        title={
          <Space>
            <GlobalOutlined />
            <span>{t('systemSettings.language.title') || '多语言设置'}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
      >
        <Form
          form={languageForm}
          layout="vertical"
          onFinish={handleLanguageSubmit}
          size={isMobile ? 'middle' : 'large'}
          initialValues={{ language: currentLang }}
        >
          <Form.Item
            label={t('systemSettings.language.currentLanguage') || '当前语言'}
            name="language"
            rules={[{ required: true, message: t('systemSettings.language.languageRequired') || '请选择语言' }]}
          >
            <Select
              options={[
                { value: 'auto', label: t('languageSettings.followSystem') || '跟随系统' },
                { value: 'zh-CN', label: '简体中文' },
                { value: 'zh-TW', label: '繁體中文' },
                { value: 'en', label: 'English' }
              ]}
            />
          </Form.Item>
          {currentLang === 'auto' && (
            <Form.Item>
              <Text type="secondary" style={{ fontSize: '12px' }}>
                {t('languageSettings.currentSystemLanguage') || '当前系统语言'}: {
                  detectSystemLanguage() === 'zh-CN' ? '简体中文' :
                    detectSystemLanguage() === 'zh-TW' ? '繁體中文' : 'English'
                }
              </Text>
            </Form.Item>
          )}
          <Form.Item>
            <Button type="primary" htmlType="submit" icon={<SaveOutlined />}>
              {t('common.save') || '保存设置'}
            </Button>
          </Form.Item>
        </Form>
      </Card>

      <Card
        title={
          <Space>
            <NotificationOutlined />
            <span>{t('systemSettings.notification.title') || '消息推送设置'}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
        extra={
          <Button
            type="primary"
            icon={<RightOutlined />}
            onClick={() => navigate('/system-settings/notification')}
          >
            {t('notificationSettings.title')}
          </Button>
        }
      >
        <Paragraph type="secondary" style={{ marginBottom: 16 }}>
          {t('notificationSettings.botConfig')}、{t('notificationSettings.templateConfig')}等请在独立页面中配置。
        </Paragraph>
        <Button
          type="link"
          icon={<RightOutlined />}
          onClick={() => navigate('/system-settings/notification')}
          style={{ padding: 0 }}
        >
          {t('notificationSettings.title')} →
        </Button>
      </Card>

      <Card
        title={
          <Space>
            <LinkOutlined />
            <span>{t('systemSettings.proxy.title') || '代理设置'}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="Builder 与自动赎回已改到账号内配置"
          description="请到 账户管理 或 账户详情 页面为每个账号单独设置 Builder 和自动赎回。"
        />

        <Form
          form={proxyForm}
          layout="vertical"
          onFinish={handleProxySubmit}
          size={isMobile ? 'middle' : 'large'}
          autoComplete="off"
        >
          <Form.Item label={t('proxySettings.enabled') || '启用代理'} name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>

          <Form.Item
            label={t('proxySettings.host') || '代理主机'}
            name="host"
            rules={[
              { required: true, message: t('proxySettings.hostRequired') || '请输入代理主机地址' },
              { pattern: /^[\w.-]+$/, message: t('proxySettings.hostInvalid') || '请输入有效的主机地址' }
            ]}
          >
            <Input placeholder={t('proxySettings.hostPlaceholder') || '例如：127.0.0.1 或 proxy.example.com'} />
          </Form.Item>

          <Form.Item
            label={t('proxySettings.port') || '代理端口'}
            name="port"
            rules={[
              { required: true, message: t('proxySettings.portRequired') || '请输入代理端口' },
              { type: 'number', min: 1, max: 65535, message: t('proxySettings.portInvalid') || '端口必须在 1-65535 之间' }
            ]}
          >
            <InputNumber min={1} max={65535} style={{ width: '100%' }} placeholder={t('proxySettings.portPlaceholder') || '例如：8888'} />
          </Form.Item>

          <Form.Item label={t('proxySettings.username') || '代理用户名（可选）'} name="username">
            <Input
              placeholder={t('proxySettings.usernamePlaceholder') || '如果代理需要认证，请输入用户名'}
              autoComplete="username"
            />
          </Form.Item>

          <Form.Item
            label={t('proxySettings.password') || '代理密码（可选）'}
            name="password"
            help={currentProxyConfig ? (t('proxySettings.passwordHelpUpdate') || '留空则不更新密码，输入新密码则更新') : (t('proxySettings.passwordHelp') || '如果代理需要认证，请输入密码')}
          >
            <Input.Password
              name="proxyPassword"
              placeholder={currentProxyConfig ? (t('proxySettings.passwordPlaceholderUpdate') || '留空则不更新密码') : (t('proxySettings.passwordPlaceholder') || '如果代理需要认证，请输入密码')}
              autoComplete="new-password"
            />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={proxyLoading}>
                {t('common.save') || '保存配置'}
              </Button>
              <Button icon={<CheckCircleOutlined />} onClick={handleProxyCheck} loading={proxyChecking}>
                {t('proxySettings.check') || '检查代理'}
              </Button>
              {proxyCheckResult && (
                <Button icon={<ReloadOutlined />} onClick={fetchProxyConfig}>
                  {t('common.refresh') || '刷新配置'}
                </Button>
              )}
            </Space>
          </Form.Item>
        </Form>

        {proxyCheckResult && (
          <Alert
            type={proxyCheckResult.success ? 'success' : 'error'}
            message={proxyCheckResult.success ? (t('proxySettings.checkSuccess') || '代理检查成功') : (t('proxySettings.checkFailed') || '代理检查失败')}
            description={
              <div>
                <Text>{proxyCheckResult.message}</Text>
                {(proxyCheckResult.responseTime !== undefined || proxyCheckResult.latency !== undefined) && (
                  <div style={{ marginTop: '8px' }}>
                    <Text type="secondary">
                      {t('proxySettings.latency') || '延迟'}: {(proxyCheckResult.latency ?? proxyCheckResult.responseTime) ?? 0}ms
                    </Text>
                  </div>
                )}
              </div>
            }
            style={{ marginTop: '16px' }}
            showIcon
          />
        )}
      </Card>
    </div>
  )
}

export default SystemSettings
