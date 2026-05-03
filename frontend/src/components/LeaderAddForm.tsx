import { useState } from 'react'
import { Button, Form, Input, Space, message } from 'antd'
import type { FormInstance } from 'antd'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import type { LeaderAddRequest } from '../types'
import { extractApiErrorMessage } from '../utils/apiError'
import { isValidWalletAddress } from '../utils'

type LeaderAddFormValues = Pick<LeaderAddRequest, 'leaderAddress' | 'leaderName' | 'customGroup' | 'remark' | 'website'>

interface LeaderAddFormProps {
  form: FormInstance<LeaderAddFormValues>
  onSuccess?: (leaderId: number) => void
  onCancel?: () => void
  showCancelButton?: boolean
}

const LeaderAddForm: React.FC<LeaderAddFormProps> = ({
  form,
  onSuccess,
  onCancel,
  showCancelButton = true,
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const addFailedMessage = t('leaderAdd.addFailed') || '添加 Leader 失败'

  const handleSubmit = async (values: LeaderAddFormValues) => {
    setLoading(true)

    try {
      const response = await apiService.leaders.add({
        leaderAddress: values.leaderAddress.trim(),
        leaderName: values.leaderName?.trim(),
        customGroup: values.customGroup?.trim(),
        remark: values.remark?.trim(),
        website: values.website?.trim(),
      })

      if (response.data.code === 0) {
        if (response.data.data && onSuccess) {
          onSuccess(response.data.data.id)
        }
        return
      }

      message.error(response.data.msg || addFailedMessage)
    } catch (error: unknown) {
      message.error(extractApiErrorMessage(error, addFailedMessage))
    } finally {
      setLoading(false)
    }
  }

  return (
    <Form
      form={form}
      layout="vertical"
      onFinish={handleSubmit}
      size={isMobile ? 'middle' : 'large'}
    >
      <Form.Item
        label={t('leaderAdd.leaderAddress') || 'Leader 钱包地址'}
        name="leaderAddress"
        rules={[
          { required: true, message: t('leaderAdd.leaderAddressRequired') || '请输入 Leader 钱包地址' },
          {
            validator: (_, value) => {
              if (!value) {
                return Promise.reject(new Error(t('leaderAdd.leaderAddressRequired') || '请输入 Leader 钱包地址'))
              }

              if (!isValidWalletAddress(value.trim())) {
                return Promise.reject(new Error(t('leaderAdd.leaderAddressInvalid') || '钱包地址格式不正确'))
              }

              return Promise.resolve()
            },
          },
        ]}
        tooltip={t('leaderAdd.leaderAddressTooltip') || '系统会监控这个 Leader 的交易'}
      >
        <Input placeholder="0x..." style={{ fontFamily: 'monospace' }} />
      </Form.Item>

      <Form.Item
        label={t('leaderAdd.leaderName') || 'Leader 名称'}
        name="leaderName"
        tooltip={t('leaderAdd.leaderNameTooltip') || '可选，用于识别 Leader'}
      >
        <Input placeholder={t('leaderAdd.leaderNamePlaceholder') || '可选，用于识别 Leader'} />
      </Form.Item>

      <Form.Item
        label={t('leaderAdd.customGroup', { defaultValue: '自定义分组' })}
        name="customGroup"
        tooltip={t('leaderAdd.customGroupTooltip', { defaultValue: '可选，用于把 Leader 放到你的自定义分组里' })}
      >
        <Input placeholder={t('leaderAdd.customGroupPlaceholder', { defaultValue: '可选，例如：重点观察、体育组、政治组' })} />
      </Form.Item>

      <Form.Item
        label={t('leaderAdd.remark') || 'Leader 备注'}
        name="remark"
        tooltip={t('leaderAdd.remarkTooltip') || '可选，用于记录 Leader 备注'}
      >
        <Input.TextArea
          placeholder={t('leaderAdd.remarkPlaceholder') || '可选，用于记录 Leader 备注信息'}
          rows={3}
          maxLength={500}
          showCount
        />
      </Form.Item>

      <Form.Item
        label={t('leaderAdd.website') || 'Leader 网站'}
        name="website"
        tooltip={t('leaderAdd.websiteTooltip') || '可选，Leader 的网站链接'}
        rules={[
          {
            type: 'url',
            message: t('leaderAdd.websiteInvalid') || '请输入有效的 URL 地址',
          },
        ]}
      >
        <Input placeholder={t('leaderAdd.websitePlaceholder') || '可选，例如：https://example.com'} />
      </Form.Item>

      <Form.Item>
        <Space>
          <Button
            type="primary"
            htmlType="submit"
            loading={loading}
            size={isMobile ? 'middle' : 'large'}
          >
            {t('leaderAdd.add') || '添加 Leader'}
          </Button>
          {showCancelButton && onCancel && (
            <Button onClick={onCancel}>
              {t('common.cancel') || '取消'}
            </Button>
          )}
        </Space>
      </Form.Item>
    </Form>
  )
}

export default LeaderAddForm
