import { useEffect } from 'react'
import { Alert, Button, Card, Form, Input, Space, Switch, Tag, Typography, message } from 'antd'
import { SaveOutlined } from '@ant-design/icons'
import type { Account, AccountUpdateRequest } from '../types'
import { useAccountStore } from '../store/accountStore'

const { Text } = Typography

interface AccountBuilderConfigCardProps {
  account: Account
  compact?: boolean
  onSaved?: (account: Account) => void
}

interface AccountBuilderConfigFormValues {
  builderApiKey?: string
  builderSecret?: string
  builderPassphrase?: string
  autoRedeemEnabled?: boolean
}

const AccountBuilderConfigCard: React.FC<AccountBuilderConfigCardProps> = ({
  account,
  compact = false,
  onSaved
}) => {
  const [form] = Form.useForm<AccountBuilderConfigFormValues>()
  const { updateAccount } = useAccountStore()

  useEffect(() => {
    form.setFieldsValue({
      builderApiKey: account.builderApiKeyDisplay || '',
      builderSecret: account.builderSecretDisplay || '',
      builderPassphrase: account.builderPassphraseDisplay || '',
      autoRedeemEnabled: account.autoRedeemEnabled ?? true
    })
  }, [account, form])

  const handleSubmit = async (values: AccountBuilderConfigFormValues) => {
    const payload: AccountUpdateRequest = {
      accountId: account.id,
      builderApiKey: values.builderApiKey?.trim() || undefined,
      builderSecret: values.builderSecret?.trim() || undefined,
      builderPassphrase: values.builderPassphrase?.trim() || undefined,
      autoRedeemEnabled: values.autoRedeemEnabled ?? true
    }

    try {
      const updated = await updateAccount(payload)
      message.success('账户 Builder 配置已保存')
      onSaved?.(updated)
    } catch (error: any) {
      message.error(error.message || '保存账户 Builder 配置失败')
    }
  }

  return (
    <Card
      size={compact ? 'small' : 'default'}
      title={
        <Space>
          <span>Builder 与自动赎回</span>
          <Tag color={account.builderConfigured ? 'green' : 'default'}>
            {account.builderConfigured ? '已配置' : '未配置'}
          </Tag>
          <Tag color={(account.autoRedeemEnabled ?? true) ? 'blue' : 'default'}>
            {(account.autoRedeemEnabled ?? true) ? '自动赎回开启' : '自动赎回关闭'}
          </Tag>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="这里配置当前账户自己的 Builder，不再使用系统全局 Builder。"
        description={
          <div>
            <div>`Magic` 账户必须配置这三项，`Safe` 账户可选。</div>
            <div>不清楚填什么时，Builder API Key / Secret / Passphrase 来自 Polymarket Builder 页面。</div>
          </div>
        }
      />

      <Form<AccountBuilderConfigFormValues>
        form={form}
        layout="vertical"
        onFinish={handleSubmit}
        autoComplete="off"
      >
        <Form.Item label="Builder API Key" name="builderApiKey">
          <Input autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>

        <Form.Item label="Builder Secret" name="builderSecret">
          <Input.Password autoComplete="new-password" style={{ fontFamily: 'monospace' }} />
        </Form.Item>

        <Form.Item label="Builder Passphrase" name="builderPassphrase">
          <Input.Password autoComplete="new-password" style={{ fontFamily: 'monospace' }} />
        </Form.Item>

        <Form.Item
          label="自动赎回"
          name="autoRedeemEnabled"
          valuePropName="checked"
          extra={<Text type="secondary">只影响当前账户。</Text>}
        >
          <Switch />
        </Form.Item>

        <Form.Item style={{ marginBottom: 0 }}>
          <Button type="primary" htmlType="submit" icon={<SaveOutlined />}>
            保存当前账户配置
          </Button>
        </Form.Item>
      </Form>
    </Card>
  )
}

export default AccountBuilderConfigCard
