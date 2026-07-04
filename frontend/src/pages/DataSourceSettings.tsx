import { useEffect, useState } from 'react'
import { Button, Card, Form, Input, InputNumber, message, Switch, Typography } from 'antd'
import { apiClient } from '../services/api'
import { PageShell } from './PageShell'

const { Text } = Typography
const SUPPORTED_SOURCE_KEYS = new Set(['crown'])

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
      .then((response) => form.setFieldsValue({
        configs: response.data.data.filter((config) => SUPPORTED_SOURCE_KEYS.has(config.sourceKey)),
      }))
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
    <PageShell
      title="数据源设置"
      description="启用后后端会按间隔自动采集，皇冠需要填写平台网址、账号和密码。"
      actions={<Button type="primary" onClick={save} loading={saving}>保存</Button>}
      className="data-source-settings-page"
    >
      <Form form={form} layout="vertical" disabled={loading}>
        <Form.List name="configs">
          {(fields) => (
            <div className="page-form-grid">
              {fields.map((field) => {
                const sourceKey = form.getFieldValue(['configs', field.name, 'sourceKey'])
                return (
                  <Card key={field.key} title={form.getFieldValue(['configs', field.name, 'displayName'])}>
                    <Form.Item name={[field.name, 'sourceKey']} hidden><Input /></Form.Item>
                    <Form.Item name={[field.name, 'displayName']} hidden><Input /></Form.Item>
                    <Form.Item name={[field.name, 'enabled']} label="启用" valuePropName="checked">
                      <Switch />
                    </Form.Item>
                    <Form.Item name={[field.name, 'username']} label="账号">
                      <Input autoComplete="off" />
                    </Form.Item>
                    <Form.Item name={[field.name, 'password']} label="密码">
                      <Input.Password autoComplete="new-password" />
                    </Form.Item>
                    {sourceKey === 'crown' && (
                      <Form.Item name={[field.name, 'queryKeyword']} label="平台网址">
                        <Input placeholder="https://your-crown-host.example/" />
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

      <Text type="secondary" className="page-panel-note">
        保存后由后端采集调度按间隔执行；本页不展示采集结果，状态请到数据源状态页查看。
      </Text>
    </PageShell>
  )
}

export default DataSourceSettings
