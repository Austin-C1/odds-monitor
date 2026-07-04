import { Form, Input, Modal } from 'antd'
import { DEFAULT_CROWN_LOGIN_URL } from '../crownBettingAccounts'
import type { CrownAccountFormValues } from '../crownBettingTypes'

type CrownAccountModalProps = {
  open: boolean
  onCancel: () => void
  onAdd: (values: CrownAccountFormValues) => void
}

export const CrownAccountModal = ({ open, onCancel, onAdd }: CrownAccountModalProps) => {
  const [form] = Form.useForm<CrownAccountFormValues>()

  const handleAdd = async () => {
    const values = await form.validateFields()
    onAdd(values)
    form.resetFields()
  }

  return (
    <Modal
      title="添加皇冠账号"
      open={open}
      okText="添加"
      cancelText="取消"
      onOk={handleAdd}
      onCancel={onCancel}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        preserve={false}
        initialValues={{ loginUrl: DEFAULT_CROWN_LOGIN_URL }}
      >
        <Form.Item
          label="账号名称"
          name="displayName"
          rules={[{ required: true, message: '请输入账号名称' }]}
        >
          <Input placeholder="例如：皇冠主账号" />
        </Form.Item>
        <Form.Item
          label="登录账号"
          name="loginName"
          rules={[{ required: true, message: '请输入登录账号' }]}
        >
          <Input placeholder="皇冠登录账号" />
        </Form.Item>
        <Form.Item
          label="AdsPower 档案 ID / 编号"
          name="adsPowerProfileId"
        >
          <Input placeholder="可不填；也可填 AdsPower 环境ID或左侧编号，程序会自动匹配已登录环境" />
        </Form.Item>
        <Form.Item
          label="登录网站"
          name="loginUrl"
          rules={[
            { required: true, message: '请输入登录网站' },
            { type: 'url', message: '请输入完整网站地址，例如 https://your-crown-host.example/' },
          ]}
        >
          <Input placeholder="https://your-crown-host.example/" />
        </Form.Item>
        <Form.Item label="备注" name="note">
          <Input.TextArea rows={3} placeholder="可填写用途、归属或异常说明" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
