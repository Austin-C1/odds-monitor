import { useEffect, useState } from 'react'
import { Alert, Button, Form, Input, Modal, Select, Space, Switch, message } from 'antd'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../../services/api'
import type { CopyTrading, CopyTradingEditFormValues, CopyTradingUpdateRequest } from '../../types'

interface EditModalProps {
  open: boolean
  onClose: () => void
  copyTradingId: string
  onSuccess?: () => void
}

const EditModal: React.FC<EditModalProps> = ({ open, onClose, copyTradingId, onSuccess }) => {
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [form] = Form.useForm<CopyTradingEditFormValues>()
  const [loading, setLoading] = useState(false)
  const [fetching, setFetching] = useState(false)
  const [copyTrading, setCopyTrading] = useState<CopyTrading | null>(null)

  useEffect(() => {
    if (!open || !copyTradingId) {
      return
    }
    fetchCopyTrading(Number(copyTradingId))
  }, [open, copyTradingId])

  const fetchCopyTrading = async (targetId: number) => {
    setFetching(true)
    try {
      const response = await apiService.copyTrading.detail({ copyTradingId: targetId })
      if (response.data.code === 0 && response.data.data) {
        const target = response.data.data
        setCopyTrading(target)
        form.setFieldsValue({
          configName: target.configName,
          supportSell: target.supportSell,
          pushFailedOrders: target.pushFailedOrders,
          pushFilteredOrders: target.pushFilteredOrders,
        })
      } else {
        message.error(response.data.msg || '获取跟单配置失败')
        onClose()
      }
    } catch (error: any) {
      message.error(error.message || '获取跟单配置失败')
      onClose()
    } finally {
      setFetching(false)
    }
  }

  const handleSubmit = async (values: CopyTradingEditFormValues) => {
    if (!copyTrading) {
      return
    }

    setLoading(true)
    try {
      const request: CopyTradingUpdateRequest = {
        copyTradingId: copyTrading.id,
        enabled: copyTrading.enabled,
        configName: values.configName?.trim(),
        supportSell: values.supportSell,
        pushFailedOrders: values.pushFailedOrders,
        pushFilteredOrders: values.pushFilteredOrders,
      }

      const response = await apiService.copyTrading.update(request)
      if (response.data.code === 0) {
        message.success('跟单配置已更新')
        onClose()
        onSuccess?.()
      } else {
        message.error(response.data.msg || '更新跟单配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '更新跟单配置失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      title="编辑跟单"
      open={open}
      onCancel={onClose}
      footer={null}
      width={isMobile ? '94%' : 560}
      destroyOnHidden
      forceRender
    >
      {fetching || !copyTrading ? (
        <div style={{ padding: '48px 0', textAlign: 'center' }}>加载中...</div>
      ) : (
        <>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="这里只修改当前跟单的基础设置，账户、Leader 和其他策略参数保持不变。"
          />

          <Form form={form} layout="vertical" onFinish={handleSubmit}>
            <Form.Item
              label="配置名称"
              name="configName"
              rules={[
                { required: true, message: '请输入配置名称' },
                { whitespace: true, message: '配置名称不能为空' },
              ]}
            >
              <Input maxLength={255} />
            </Form.Item>

            <Form.Item label="账户">
              <Select
                disabled
                value={copyTrading.accountId}
                options={[
                  {
                    value: copyTrading.accountId,
                    label: `${copyTrading.accountName || `账户 ${copyTrading.accountId}`} (${copyTrading.walletAddress.slice(0, 6)}...${copyTrading.walletAddress.slice(-4)})`,
                  },
                ]}
              />
            </Form.Item>

            <Form.Item label="Leader">
              <Select
                disabled
                value={copyTrading.leaderId}
                options={[
                  {
                    value: copyTrading.leaderId,
                    label: `${copyTrading.leaderName || `Leader ${copyTrading.leaderId}`} (${copyTrading.leaderAddress.slice(0, 6)}...${copyTrading.leaderAddress.slice(-4)})`,
                  },
                ]}
              />
            </Form.Item>

            <Form.Item label="允许跟卖" name="supportSell" valuePropName="checked">
              <Switch checkedChildren="开启" unCheckedChildren="关闭" />
            </Form.Item>

            <Form.Item label="推送失败订单" name="pushFailedOrders" valuePropName="checked">
              <Switch checkedChildren="开启" unCheckedChildren="关闭" />
            </Form.Item>

            <Form.Item label="推送已过滤订单" name="pushFilteredOrders" valuePropName="checked">
              <Switch checkedChildren="开启" unCheckedChildren="关闭" />
            </Form.Item>

            <Form.Item style={{ marginBottom: 0 }}>
              <Space>
                <Button type="primary" htmlType="submit" loading={loading}>
                  保存
                </Button>
                <Button onClick={onClose}>取消</Button>
              </Space>
            </Form.Item>
          </Form>
        </>
      )}
    </Modal>
  )
}

export default EditModal
