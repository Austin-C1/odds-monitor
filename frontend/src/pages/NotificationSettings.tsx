import { useEffect, useState } from 'react'
import { Card, Table, Button, Space, Tag, Popconfirm, message, Typography, Modal, Form, Input, Switch } from 'antd'
import { PlusOutlined, EditOutlined, DeleteOutlined, SendOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import type { NotificationConfig, NotificationConfigRequest, NotificationConfigUpdateRequest } from '../types'
import { useMediaQuery } from 'react-responsive'
import { TelegramConfigForm, DiscordConfigForm, SlackConfigForm } from '../components/notifications'

const { Title, Text } = Typography

const NotificationSettings: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [configs, setConfigs] = useState<NotificationConfig[]>([])
  const [loading, setLoading] = useState(false)
  const [modalVisible, setModalVisible] = useState(false)
  const [editingConfig, setEditingConfig] = useState<NotificationConfig | null>(null)
  const [form] = Form.useForm()
  const [testLoading, setTestLoading] = useState(false)
  
  useEffect(() => {
    fetchConfigs()
  }, [])
  
  const fetchConfigs = async () => {
    setLoading(true)
    try {
      const response = await apiService.notifications.list({ type: 'telegram' })
      if (response.data.code === 0 && response.data.data) {
        setConfigs(response.data.data)
      } else {
        message.error(response.data.msg || t('notificationSettings.fetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }
  
  const handleCreate = () => {
    setEditingConfig(null)
    form.resetFields()
    form.setFieldsValue({
      type: 'telegram',
      enabled: true,
      config: {
        botToken: '',
        chatIds: []
      }
    })
    setModalVisible(true)
  }
  
  const handleEdit = (config: NotificationConfig) => {
    setEditingConfig(config)
    
    // 处理配置数据：后端返回的是 NotificationConfigData.Telegram 结构
    // 结构可能是: { data: { botToken: string, chatIds: string[] } } 或直接 { botToken: string, chatIds: string[] }
    let botToken = ''
    let chatIds = ''
    
    if (config.config) {
      // 检查是否是嵌套结构 (NotificationConfigData.Telegram)
      if ('data' in config.config && config.config.data) {
        const data = config.config.data as any
        botToken = data.botToken || ''
        if (data.chatIds) {
          if (Array.isArray(data.chatIds)) {
            chatIds = data.chatIds.join(',')
          } else if (typeof data.chatIds === 'string') {
            chatIds = data.chatIds
          }
        }
      } else {
        // 直接结构 (TelegramConfigData)
        if ('botToken' in config.config) {
          botToken = (config.config as any).botToken || ''
        }
        if ('chatIds' in config.config) {
          const ids = (config.config as any).chatIds
          if (Array.isArray(ids)) {
            chatIds = ids.join(',')
          } else if (typeof ids === 'string') {
            chatIds = ids
          }
        }
      }
    }
    
    form.setFieldsValue({
      type: config.type,
      name: config.name,
      enabled: config.enabled,
      config: {
        botToken: botToken,
        chatIds: chatIds
      }
    })
    setModalVisible(true)
  }
  
  const handleDelete = async (id: number) => {
    try {
      const response = await apiService.notifications.delete({ id })
      if (response.data.code === 0) {
        message.success(t('notificationSettings.deleteSuccess'))
        fetchConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.deleteFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.deleteFailed'))
    }
  }
  
  const handleUpdateEnabled = async (id: number, enabled: boolean) => {
    try {
      const response = await apiService.notifications.updateEnabled({ id, enabled })
      if (response.data.code === 0) {
        message.success(enabled ? t('notificationSettings.enableSuccess') : t('notificationSettings.disableSuccess'))
        fetchConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.updateStatusFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.updateStatusFailed'))
    }
  }
  
  const handleTest = async () => {
    setTestLoading(true)
    try {
      const response = await apiService.notifications.test({ message: '这是一条测试消息' })
      if (response.data.code === 0 && response.data.data) {
        message.success(t('notificationSettings.testSuccess'))
      } else {
        message.error(response.data.msg || t('notificationSettings.testFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.testFailed'))
    } finally {
      setTestLoading(false)
    }
  }
  
  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      
      // 处理 chatIds：如果是字符串，转换为数组
      const chatIds = typeof values.config.chatIds === 'string' 
        ? values.config.chatIds.split(',').map((id: string) => id.trim()).filter((id: string) => id)
        : values.config.chatIds || []
      
      const configData: NotificationConfigRequest | NotificationConfigUpdateRequest = {
        type: values.type,
        name: values.name,
        enabled: values.enabled,
        config: {
          botToken: values.config.botToken,
          chatIds: chatIds
        }
      }
      
      if (editingConfig?.id) {
        // 更新
        const updateData = {
          ...configData,
          id: editingConfig.id
        } as NotificationConfigUpdateRequest
        
        const response = await apiService.notifications.update(updateData)
        if (response.data.code === 0) {
          message.success(t('notificationSettings.updateSuccess'))
          setModalVisible(false)
          fetchConfigs()
        } else {
          message.error(response.data.msg || t('notificationSettings.updateFailed'))
        }
      } else {
        // 创建
        const response = await apiService.notifications.create(configData)
        if (response.data.code === 0) {
          message.success(t('notificationSettings.createSuccess'))
          setModalVisible(false)
          fetchConfigs()
        } else {
          message.error(response.data.msg || t('notificationSettings.createFailed'))
        }
      }
    } catch (error: any) {
      if (error.errorFields) {
        // 表单验证错误
        return
      }
      message.error(error.message || t('message.error'))
    }
  }
  
  /**
   * 根据推送类型获取配置表单组件
   */
  const getConfigFormComponent = (type: string) => {
    switch (type?.toLowerCase()) {
      case 'telegram':
        return <TelegramConfigForm form={form} />
      case 'discord':
        return <DiscordConfigForm form={form} />
      case 'slack':
        return <SlackConfigForm form={form} />
      default:
        return null
    }
  }
  
  const columns = [
    {
      title: t('notificationSettings.configName'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('notificationSettings.type'),
      dataIndex: 'type',
      key: 'type',
      render: (type: string) => <Tag color="blue">{type.toUpperCase()}</Tag>
    },
    {
      title: t('notificationSettings.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      render: (enabled: boolean) => (
        <Tag color={enabled ? 'green' : 'default'}>
          {enabled ? t('notificationSettings.enabledStatus') : t('notificationSettings.disabledStatus')}
        </Tag>
      )
    },
    {
      title: t('notificationSettings.chatIds'),
      key: 'chatIds',
      render: (_: any, record: NotificationConfig) => {
        // 处理配置数据：后端返回的是 NotificationConfigData.Telegram 结构
        // 结构可能是: { data: { botToken: string, chatIds: string[] } } 或直接 { botToken: string, chatIds: string[] }
        let chatIds: string[] = []
        
        if (record.config) {
          // 检查是否是嵌套结构 (NotificationConfigData.Telegram)
          if ('data' in record.config && record.config.data) {
            const data = (record.config as any).data
            if (data.chatIds) {
              if (Array.isArray(data.chatIds)) {
                chatIds = data.chatIds.filter((id: any) => id && String(id).trim())
              } else if (typeof data.chatIds === 'string') {
                chatIds = data.chatIds.split(',').map((id: string) => id.trim()).filter((id: string) => id)
              }
            }
          } else if ('chatIds' in record.config) {
            // 直接结构 (TelegramConfigData)
            const ids: any = (record.config as any).chatIds
            if (Array.isArray(ids)) {
              chatIds = ids.filter((id: any) => id && String(id).trim())
            } else if (typeof ids === 'string') {
              chatIds = (ids as string).split(',').map((id: string) => id.trim()).filter((id: string) => id)
            }
          }
        }
        
        return chatIds.length > 0 ? (
          <Text type="secondary" style={{ fontSize: '12px' }}>
            {chatIds.join(', ')}
          </Text>
        ) : (
          <Text type="danger" style={{ fontSize: '12px' }}>{t('notificationSettings.chatIdsNotConfigured')}</Text>
        )
      }
    },
    {
      title: t('common.actions'),
      key: 'action',
      width: isMobile ? 120 : 200,
      render: (_: any, record: NotificationConfig) => (
        <Space size="small" wrap>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => handleEdit(record)}
          >
            {t('notificationSettings.edit')}
          </Button>
          <Switch
            checked={record.enabled}
            size="small"
            onChange={(checked) => handleUpdateEnabled(record.id!, checked)}
          />
          <Button
            type="link"
            size="small"
            icon={<SendOutlined />}
            loading={testLoading}
            onClick={handleTest}
          >
            {t('notificationSettings.test')}
          </Button>
          <Popconfirm
            title={t('notificationSettings.deleteConfirm')}
            onConfirm={() => handleDelete(record.id!)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
            >
              {t('notificationSettings.delete')}
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]
  
  return (
    <div style={{ padding: isMobile ? '16px' : '24px' }}>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Title level={4} style={{ margin: 0 }}>{t('notificationSettings.title')}</Title>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={handleCreate}
          >
            {t('notificationSettings.addConfig')}
          </Button>
        </div>
        
        <Table
          columns={columns}
          dataSource={configs}
          loading={loading}
          rowKey="id"
          pagination={false}
          scroll={{ x: isMobile ? 600 : 'auto' }}
        />
      </Card>
      
      <Modal
        title={editingConfig ? t('notificationSettings.editConfig') : t('notificationSettings.addConfig')}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        width={isMobile ? '90%' : 600}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
      >
        <Form
          form={form}
          layout="vertical"
        >
          <Form.Item
            name="type"
            label={t('notificationSettings.type')}
            rules={[{ required: true, message: t('notificationSettings.typeRequired') }]}
          >
            <Input disabled value="telegram" />
          </Form.Item>
          
          <Form.Item
            name="name"
            label={t('notificationSettings.configName')}
            rules={[{ required: true, message: t('notificationSettings.configNameRequired') }]}
          >
            <Input placeholder={t('notificationSettings.configNamePlaceholder')} />
          </Form.Item>
          
          <Form.Item
            name="enabled"
            label={t('notificationSettings.enabled')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          {/* 根据推送类型动态渲染配置表单组件 */}
          <Form.Item shouldUpdate={(prevValues, currentValues) => {
            // 监听 type 变化，以及编辑时 config 数据的变化
            return prevValues.type !== currentValues.type || 
                   prevValues.config !== currentValues.config
          }}>
            {() => {
              const currentType = form.getFieldValue('type') || 'telegram'
              return getConfigFormComponent(currentType)
            }}
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default NotificationSettings

