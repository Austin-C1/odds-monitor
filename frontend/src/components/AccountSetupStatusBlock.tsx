import React, { useEffect, useState } from 'react'
import { Card, Steps, Button, Space, Tag, Spin, Typography, message } from 'antd'
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  WalletOutlined,
  KeyOutlined,
  SafetyOutlined,
  LinkOutlined,
  ReloadOutlined
} from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import { getApprovalDetailDisplay } from '../utils/accountSetupApproval'

const { Paragraph, Text } = Typography

export interface SetupStatus {
  proxyDeployed: boolean
  tradingEnabled: boolean
  tokensApproved: boolean
  approvalDetails?: Record<string, string>
  error?: string
}

interface AccountSetupStatusBlockProps {
  accountId: number
  onRefresh?: () => void
  onAllCompleted?: () => void
  size?: 'small' | 'default'
  showApprovalDetails?: boolean
  // Render without an outer Card when embedded in another view.
  embedded?: boolean
}

// Keep the setup step keys aligned with the backend step numbers.
const STEP_KEYS = ['step1', 'step2', 'step3'] as const
const stepKeyToNumber = (key: string): number =>
  STEP_KEYS.indexOf(key as typeof STEP_KEYS[number]) + 1

const AccountSetupStatusBlock: React.FC<AccountSetupStatusBlockProps> = ({
  accountId,
  onRefresh,
  onAllCompleted,
  size = 'default',
  showApprovalDetails = true,
  embedded = false
}) => {
  const { t } = useTranslation()
  const [setupStatus, setSetupStatus] = useState<SetupStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [actionLoading, setActionLoading] = useState<string | null>(null)

  const fetchStatus = async () => {
    if (accountId <= 0) return
    try {
      const response = await apiService.accounts.checkSetupStatus(accountId)
      if (response.data.code === 0 && response.data.data) {
        setSetupStatus(response.data.data)
      } else {
        setSetupStatus(null)
      }
    } catch (error) {
      console.error('Failed to load account setup status', error)
      setSetupStatus(null)
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }

  useEffect(() => {
    setLoading(true)
    fetchStatus()
  }, [accountId])

  // Once setup is close to complete, keep polling until every step is ready.
  useEffect(() => {
    if (accountId <= 0 || setupStatus == null) return
    const allCompleted =
      setupStatus.proxyDeployed &&
      setupStatus.tradingEnabled &&
      setupStatus.tokensApproved
    if (allCompleted) return
    const timer = setInterval(() => {
      fetchStatus()
    }, 5000)
    return () => clearInterval(timer)
  }, [accountId, setupStatus?.proxyDeployed, setupStatus?.tradingEnabled, setupStatus?.tokensApproved])

  // All setup steps are complete only when every required flag is true.
  const allCompleted =
    setupStatus != null &&
    setupStatus.proxyDeployed &&
    setupStatus.tradingEnabled &&
    setupStatus.tokensApproved
  useEffect(() => {
    if (allCompleted) onAllCompleted?.()
  }, [allCompleted, onAllCompleted])

  const handleRefresh = async () => {
    setRefreshing(true)
    await fetchStatus()
    onRefresh?.()
  }

  const handleStepAction = async (key: string) => {
    const stepNum = stepKeyToNumber(key)
    if (stepNum < 1) return
    setActionLoading(key)
    try {
      const response = await apiService.accounts.executeSetupStep(accountId, stepNum)
      const res = response.data
      if (res.code !== 0) {
        message.error(res.msg || t('accountSetup.actionFailed'))
        return
      }
      const data = res.data
      if (data?.redirectUrl) {
        window.open(data.redirectUrl, '_blank')
      }
      if (data?.success !== false) {
        await fetchStatus()
        onRefresh?.()
        if (data?.transactionHash) {
          message.success(t('accountSetup.actionSuccess'))
        }
      }
    } catch (err) {
      message.error(t('accountSetup.actionFailed'))
    } finally {
      setActionLoading(null)
    }
  }

  if (loading && !setupStatus) {
    const loadingContent = (
      <div style={{ textAlign: 'center', padding: '24px 0' }}>
        <Spin />
      </div>
    )
    return embedded ? <div>{loadingContent}</div> : (
      <Card title={t('accountSetup.title')} size={size}>{loadingContent}</Card>
    )
  }

  if (!setupStatus) {
    const errorContent = (
      <>
        <Text type="secondary">{t('accountSetup.error.description')}</Text>
        <div style={{ marginTop: 12 }}>
          <Button icon={<ReloadOutlined />} onClick={handleRefresh} loading={refreshing}>
            {t('accountSetup.refresh')}
          </Button>
        </div>
      </>
    )
    return embedded ? <div>{errorContent}</div> : (
      <Card title={t('accountSetup.title')} size={size}>{errorContent}</Card>
    )
  }

  const steps = [
    {
      key: 'step1',
      title: t('accountSetup.step1.title'),
      description: t('accountSetup.step1.description'),
      icon: <WalletOutlined />,
      completed: setupStatus.proxyDeployed,
      actionLabel: t('accountSetup.step1.action')
    },
    {
      key: 'step2',
      title: t('accountSetup.step2.title'),
      description: t('accountSetup.step2.description'),
      icon: <KeyOutlined />,
      completed: setupStatus.tradingEnabled,
      actionLabel: t('accountSetup.step2.action')
    },
    {
      key: 'step3',
      title: t('accountSetup.step3.title'),
      description: t('accountSetup.step3.description'),
      icon: <SafetyOutlined />,
      completed: setupStatus.tokensApproved,
      actionLabel: t('accountSetup.step3.action')
    }
  ]

  const stepsContent = (
    <>
      <Steps
        direction="vertical"
        current={steps.findIndex(s => !s.completed)}
        size="small"
        style={{ marginBottom: 16 }}
      >
        {steps.map((step) => (
          <Steps.Step
            key={step.key}
            title={
              <Space>
                <span>{step.title}</span>
                {step.completed ? (
                  <Tag color="success" icon={<CheckCircleOutlined />}>
                    {t('accountSetup.completed')}
                  </Tag>
                ) : (
                  <Tag color="warning" icon={<CloseCircleOutlined />}>
                    {t('accountSetup.pending')}
                  </Tag>
                )}
              </Space>
            }
            description={
              <div style={{ marginTop: 8 }}>
                <Paragraph style={{ marginBottom: 8, fontSize: 14, color: '#666' }}>
                  {step.description}
                </Paragraph>
                {!step.completed && (
                  <Button
                    type="primary"
                    size="small"
                    icon={step.icon}
                    loading={actionLoading === step.key}
                    onClick={() => handleStepAction(step.key)}
                  >
                    {step.actionLabel}
                  </Button>
                )}
              </div>
            }
          />
        ))}
      </Steps>

      {showApprovalDetails && setupStatus.approvalDetails && Object.keys(setupStatus.approvalDetails).length > 0 && (
        <Card size="small" title={t('accountSetup.approvalDetails.title')} bordered={false} style={{ background: '#fafafa' }}>
          {Object.entries(setupStatus.approvalDetails).map(([token, status]) => {
            const detail = getApprovalDetailDisplay(token, status)

            return (
              <div key={token} style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between' }}>
                <Text>{detail.labelKey ? t(detail.labelKey) : detail.fallbackLabel}</Text>
                <Tag color={detail.tagColor}>
                  {detail.valueKey ? t(detail.valueKey) : detail.fallbackValue}
                </Tag>
              </div>
            )
          })}
        </Card>
      )}

      {allCompleted && (
        <Card bordered={false} style={{ background: '#f6ffed', marginTop: 12 }}>
          <Space align="start">
            <LinkOutlined style={{ color: '#52c41a', marginTop: 4 }} />
            <div>
              <Text strong>{t('accountSetup.success.title')}</Text>
              <Paragraph type="secondary" style={{ marginBottom: 0 }}>
                {t('accountSetup.success.description')}
              </Paragraph>
            </div>
          </Space>
        </Card>
      )}
    </>
  )

  return embedded ? <div>{stepsContent}</div> : (
    <Card title={t('accountSetup.title')} size={size}>
      {stepsContent}
    </Card>
  )
}

export default AccountSetupStatusBlock
