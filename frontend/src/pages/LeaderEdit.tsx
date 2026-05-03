import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Card, Form, Input, Button, message, Typography, Space, Spin } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import type { Leader, LeaderUpdateRequest } from '../types'

type LeaderEditFormValues = Pick<LeaderUpdateRequest, 'leaderName' | 'customGroup' | 'remark' | 'website'>

const { Title } = Typography

const LeaderEdit: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [form] = Form.useForm<LeaderEditFormValues>()
  const [loading, setLoading] = useState(false)
  const [fetching, setFetching] = useState(true)
  const leaderId = searchParams.get('id')

  useEffect(() => {
    if (leaderId) {
      void fetchLeaderDetail(parseInt(leaderId, 10))
      return
    }

    message.error(t('leaderEdit.invalidId') || 'Leader ID 无效')
    navigate('/leaders')
  }, [leaderId, navigate, t])

  const fetchLeaderDetail = async (id: number) => {
    setFetching(true)
    try {
      const response = await apiService.leaders.detail({ leaderId: id })
      if (response.data.code === 0 && response.data.data) {
        const leader: Leader = response.data.data
        form.setFieldsValue({
          leaderName: leader.leaderName || '',
          customGroup: leader.customGroup || '',
          remark: leader.remark || '',
          website: leader.website || '',
        })
      } else {
        message.error(response.data.msg || t('leaderEdit.fetchFailed') || '获取 Leader 详情失败')
        navigate('/leaders')
      }
    } catch (error: any) {
      message.error(error.message || t('leaderEdit.fetchFailed') || '获取 Leader 详情失败')
      navigate('/leaders')
    } finally {
      setFetching(false)
    }
  }

  const handleSubmit = async (values: LeaderEditFormValues) => {
    if (!leaderId) {
      message.error(t('leaderEdit.invalidId') || 'Leader ID 无效')
      return
    }

    setLoading(true)
    try {
      const response = await apiService.leaders.update({
        leaderId: parseInt(leaderId, 10),
        leaderName: values.leaderName?.trim(),
        customGroup: values.customGroup?.trim(),
        remark: values.remark?.trim(),
        website: values.website?.trim(),
      })

      if (response.data.code === 0) {
        message.success(t('leaderEdit.saveSuccess') || '更新 Leader 成功')
        navigate('/leaders')
      } else {
        message.error(response.data.msg || t('leaderEdit.saveFailed') || '更新 Leader 失败')
      }
    } catch (error: any) {
      message.error(error.message || t('leaderEdit.saveFailed') || '更新 Leader 失败')
    } finally {
      setLoading(false)
    }
  }

  if (fetching) {
    return (
      <div style={{ textAlign: 'center', padding: '40px' }}>
        <Spin size="large" />
      </div>
    )
  }

  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/leaders')}
          style={{ marginBottom: '16px' }}
        >
          返回
        </Button>
        <Title level={2} style={{ margin: 0 }}>{t('leaderEdit.title') || '编辑 Leader'}</Title>
      </div>

      <Card>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item
            label={t('leaderEdit.leaderName') || 'Leader 名称'}
            name="leaderName"
            tooltip={t('leaderEdit.leaderNameTooltip') || '可选，用于识别 Leader'}
          >
            <Input placeholder={t('leaderEdit.leaderNamePlaceholder') || '可选，用于识别 Leader'} />
          </Form.Item>

          <Form.Item
            label="自定义分组"
            name="customGroup"
            tooltip="可选，用于把 Leader 放到你自定义的分组里"
          >
            <Input placeholder="可选，例如：重点观察、体育组、政治组" />
          </Form.Item>

          <Form.Item
            label={t('leaderEdit.remark') || 'Leader 备注'}
            name="remark"
            tooltip={t('leaderEdit.remarkTooltip') || '可选，用于记录 Leader 备注'}
          >
            <Input.TextArea
              placeholder={t('leaderEdit.remarkPlaceholder') || '可选，用于记录 Leader 备注'}
              rows={3}
              maxLength={500}
              showCount
            />
          </Form.Item>

          <Form.Item
            label={t('leaderEdit.website') || 'Leader 网站'}
            name="website"
            tooltip={t('leaderEdit.websiteTooltip') || '可选，Leader 的网站链接'}
            rules={[
              {
                type: 'url',
                message: t('leaderEdit.websiteInvalid') || '请输入有效的 URL 地址',
              },
            ]}
          >
            <Input placeholder={t('leaderEdit.websitePlaceholder') || '可选，例如：https://example.com'} />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                loading={loading}
                size={isMobile ? 'middle' : 'large'}
              >
                {t('leaderEdit.save') || '保存'}
              </Button>
              <Button onClick={() => navigate('/leaders')}>
                {t('leaderEdit.cancel') || '取消'}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default LeaderEdit
