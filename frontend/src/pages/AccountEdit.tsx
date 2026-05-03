import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Card, Form, Input, Button, message, Typography, Space } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useMediaQuery } from 'react-responsive'
import { useAccountStore } from '../store/accountStore'
import type { Account, AccountNameFormValues, AccountUpdateRequest } from '../types'

const { Title } = Typography

const AccountEdit: React.FC = () => {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const accountId = searchParams.get('id')

  const { fetchAccountDetail, updateAccount, loading } = useAccountStore()
  const [form] = Form.useForm<AccountNameFormValues>()
  const [account, setAccount] = useState<Account | null>(null)
  const [loadingDetail, setLoadingDetail] = useState(true)

  useEffect(() => {
    if (accountId) {
      void loadAccountDetail()
      return
    }

    message.error('账户 ID 不能为空')
    navigate('/accounts')
  }, [accountId, navigate])

  const loadAccountDetail = async () => {
    if (!accountId) return

    setLoadingDetail(true)
    try {
      const accountData = await fetchAccountDetail(Number(accountId))
      setAccount(accountData)
      form.setFieldsValue({
        accountName: accountData.accountName || '',
        remark: accountData.remark || '',
      })
    } catch (error: any) {
      message.error(error.message || '获取账户详情失败')
      navigate('/accounts')
    } finally {
      setLoadingDetail(false)
    }
  }

  const handleSubmit = async (values: AccountNameFormValues) => {
    if (!accountId) return

    try {
      const updateData: AccountUpdateRequest = {
        accountId: Number(accountId),
        accountName: values.accountName?.trim(),
        remark: values.remark?.trim(),
      }

      await updateAccount(updateData)
      message.success('更新账户成功')
      navigate(`/accounts/detail?id=${accountId}`)
    } catch (error: any) {
      message.error(error.message || '更新账户失败')
    }
  }

  if (loadingDetail) {
    return (
      <div style={{ textAlign: 'center', padding: '50px' }}>
        <div>加载中...</div>
      </div>
    )
  }

  if (!account) {
    return null
  }

  return (
    <div style={{ padding: isMobile ? 0 : undefined, margin: isMobile ? '0 -8px' : undefined }}>
      <div style={{ marginBottom: isMobile ? 12 : 16, padding: isMobile ? '0 8px' : 0 }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate(`/accounts/detail?id=${accountId}`)}
          style={{ marginBottom: 16 }}
          size={isMobile ? 'middle' : 'large'}
        >
          返回
        </Button>
        <Title level={isMobile ? 4 : 2} style={{ margin: 0, fontSize: isMobile ? 18 : undefined }}>
          编辑账户
        </Title>
      </div>

      <Card style={{ margin: isMobile ? '0 -8px' : 0, borderRadius: isMobile ? 0 : undefined }}>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item label="账户名称" name="accountName">
            <Input placeholder="账户名称（可选）" />
          </Form.Item>

          <Form.Item label="备注" name="remark">
            <Input.TextArea placeholder="备注（可选）" rows={4} maxLength={500} showCount />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                loading={loading}
                size={isMobile ? 'middle' : 'large'}
                style={isMobile ? { minHeight: 44 } : undefined}
              >
                保存
              </Button>
              <Button
                onClick={() => navigate(`/accounts/detail?id=${accountId}`)}
                size={isMobile ? 'middle' : 'large'}
                style={isMobile ? { minHeight: 44 } : undefined}
              >
                取消
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default AccountEdit
