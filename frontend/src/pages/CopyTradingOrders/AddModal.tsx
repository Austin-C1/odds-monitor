import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Form, Input, Modal, Select, Space, Switch, message } from 'antd'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../../services/api'
import { useAccountStore } from '../../store/accountStore'
import type { CopyTradingCreateFormValues, CopyTradingCreateRequest, Leader } from '../../types'

interface AddModalProps {
  open: boolean
  onClose: () => void
  onSuccess?: () => void
  preFilledConfig?: {
    accountId?: number
    leaderId?: number
    configName?: string
  }
}

const buildDefaultConfigName = () => {
  const now = new Date()
  const pad = (value: number) => value.toString().padStart(2, '0')
  return `跟单-${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

const AddModal: React.FC<AddModalProps> = ({ open, onClose, onSuccess, preFilledConfig }) => {
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [form] = Form.useForm<CopyTradingCreateFormValues>()
  const selectedAccountId = Form.useWatch('accountId', form)
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [boundLeaderIdsByAccount, setBoundLeaderIdsByAccount] = useState<Record<number, number[]>>({})
  const [loading, setLoading] = useState(false)
  const preFilledAccountId = preFilledConfig?.accountId
  const preFilledLeaderId = preFilledConfig?.leaderId
  const preFilledConfigName = preFilledConfig?.configName

  useEffect(() => {
    if (!open) {
      return
    }

    setBoundLeaderIdsByAccount({})
    void fetchAccounts()
    void fetchLeaders()
    void fetchExistingCopyTradings()
    form.setFieldsValue({
      accountId: preFilledAccountId,
      configName: preFilledConfigName || buildDefaultConfigName(),
      leaderId: preFilledLeaderId,
      supportSell: true,
      pushFailedOrders: false,
      pushFilteredOrders: false,
    })
  }, [open, preFilledAccountId, preFilledConfigName, preFilledLeaderId, fetchAccounts, form])

  const fetchLeaders = async () => {
    try {
      const response = await apiService.leaders.list({})
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      }
    } catch (error: any) {
      message.error(error.message || '获取 Leader 列表失败')
    }
  }

  const fetchExistingCopyTradings = async () => {
    try {
      const response = await apiService.copyTrading.list({})
      if (response.data.code !== 0 || !response.data.data) {
        message.error(response.data.msg || '获取跟单列表失败')
        return
      }

      const nextMap: Record<number, number[]> = {}
      response.data.data.list.forEach((item) => {
        if (!nextMap[item.accountId]) {
          nextMap[item.accountId] = []
        }

        if (!nextMap[item.accountId].includes(item.leaderId)) {
          nextMap[item.accountId].push(item.leaderId)
        }
      })

      setBoundLeaderIdsByAccount(nextMap)
    } catch (error: any) {
      message.error(error.message || '获取跟单列表失败')
    }
  }

  const hiddenLeaderIds = useMemo(() => {
    if (!selectedAccountId) {
      return new Set<number>()
    }

    return new Set(boundLeaderIdsByAccount[selectedAccountId] || [])
  }, [boundLeaderIdsByAccount, selectedAccountId])

  const selectableLeaders = useMemo(() => {
    if (!selectedAccountId) {
      return leaders
    }

    return leaders.filter((leader) => !hiddenLeaderIds.has(leader.id))
  }, [hiddenLeaderIds, leaders, selectedAccountId])

  useEffect(() => {
    if (!open || !selectedAccountId) {
      return
    }

    const currentLeaderId = form.getFieldValue('leaderId')
    if (currentLeaderId && hiddenLeaderIds.has(currentLeaderId)) {
      form.setFieldsValue({ leaderId: undefined })
    }
  }, [form, hiddenLeaderIds, open, selectedAccountId])

  const handleSubmit = async (values: CopyTradingCreateFormValues) => {
    setLoading(true)
    try {
      const request: CopyTradingCreateRequest = {
        accountId: values.accountId,
        leaderId: values.leaderId,
        configName: values.configName?.trim(),
        supportSell: values.supportSell !== false,
        pushFailedOrders: values.pushFailedOrders ?? false,
        pushFilteredOrders: values.pushFilteredOrders ?? false,
        enabled: true,
      }

      const response = await apiService.copyTrading.create(request)
      if (response.data.code === 0) {
        message.success('跟单配置已创建')
        onClose()
        onSuccess?.()
      } else {
        message.error(response.data.msg || '创建跟单配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '创建跟单配置失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      title="新增跟单"
      open={open}
      onCancel={onClose}
      footer={null}
      width={isMobile ? '94%' : 560}
      destroyOnHidden
      forceRender
      afterClose={() => form.resetFields()}
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="这里只建立账户和 Leader 的绑定关系。单笔投注范围、投入金额和上限，请到“跟单设置”页面填写。"
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
          <Input maxLength={255} placeholder="例如：BTC 跟单-1" />
        </Form.Item>

        <Form.Item label="账户" name="accountId" rules={[{ required: true, message: '请选择账户' }]}>
          <Select
            placeholder="请选择账户"
            options={accounts.map((account) => ({
              value: account.id,
              label: `${account.accountName || `账户 ${account.id}`} (${account.walletAddress.slice(0, 6)}...${account.walletAddress.slice(-4)})`,
            }))}
          />
        </Form.Item>

        <Form.Item label="Leader" name="leaderId" rules={[{ required: true, message: '请选择 Leader' }]}>
          <Select
            placeholder="请选择 Leader"
            options={selectableLeaders.map((leader) => ({
              value: leader.id,
              label: `${leader.leaderName || `Leader ${leader.id}`} (${leader.leaderAddress.slice(0, 6)}...${leader.leaderAddress.slice(-4)})`,
            }))}
          />
        </Form.Item>

        <Form.Item label="跟随卖出" name="supportSell" valuePropName="checked">
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
    </Modal>
  )
}

export default AddModal
