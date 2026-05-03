import { useEffect, useState } from 'react'
import { Button, Card, Form, Input, InputNumber, message, Space, Switch, Typography } from 'antd'
import { apiClient } from '../services/api'

const { Title, Text } = Typography

type ApiResponse<T> = { code: number; data: T; msg: string }
type DataSourceConfig = {
  sourceKey: string
  displayName: string
  enabled: boolean
  username?: string
  password?: string
  queryKeyword?: string
  intervalSeconds: number
  updatedAt: number
}

const DataSourceSettings = () => {
  const [form] = Form.useForm<{ configs: DataSourceConfig[] }>()
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    apiClient.post<ApiResponse<DataSourceConfig[]>>('/odds-monitor/data-sources/configs/list', {})
      .then((response) => form.setFieldsValue({ configs: response.data.data }))
      .finally(() => setLoading(false))
  }, [form])

  const save = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const response = await apiClient.post<ApiResponse<DataSourceConfig[]>>('/odds-monitor/data-sources/configs/save', values)
      form.setFieldsValue({ configs: response.data.data })
      message.success('配置已保存')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <Title level={3} style={{ margin: 0 }}>数据源设置</Title>
        <Text type="secondary">启用后后端会按间隔自动采集，皇冠需要填写平台网址、账号和密码。</Text>
      </div>

      <Form form={form} layout="vertical" disabled={loading}>
        <Form.List name="configs">
          {(fields) => (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(240px, 1fr))', gap: 16 }}>
              {fields.map((field) => {
                const sourceKey = form.getFieldValue(['configs', field.name, 'sourceKey'])
                return (
                  <Card key={field.key} title={form.getFieldValue(['configs', field.name, 'displayName'])}>
                    <Form.Item name={[field.name, 'sourceKey']} hidden><Input /></Form.Item>
                    <Form.Item name={[field.name, 'displayName']} hidden><Input /></Form.Item>
                    <Form.Item name={[field.name, 'enabled']} label="启用" valuePropName="checked">
                      <Switch />
                    </Form.Item>
                    {sourceKey !== 'polymarket' && (
                      <>
                        <Form.Item name={[field.name, 'username']} label="账号">
                          <Input autoComplete="off" />
                        </Form.Item>
                        <Form.Item name={[field.name, 'password']} label="密码">
                          <Input.Password autoComplete="new-password" />
                        </Form.Item>
                      </>
                    )}
                    {sourceKey === 'crown' && (
                      <Form.Item name={[field.name, 'queryKeyword']} label="平台网址">
                        <Input placeholder="https://hga038.com/" />
                      </Form.Item>
                    )}
                    {sourceKey === 'polymarket' && (
                      <Form.Item name={[field.name, 'queryKeyword']} label="查询关键词">
                        <Input placeholder="football" />
                      </Form.Item>
                    )}
                    <Form.Item name={[field.name, 'intervalSeconds']} label="采集间隔（秒）" rules={[{ required: true }]}>
                      <InputNumber min={10} style={{ width: '100%' }} />
                    </Form.Item>
                  </Card>
                )
              })}
            </div>
          )}
        </Form.List>
      </Form>

      <Button type="primary" onClick={save} loading={saving} style={{ width: 120 }}>
        保存
      </Button>
    </Space>
  )
}

export default DataSourceSettings
