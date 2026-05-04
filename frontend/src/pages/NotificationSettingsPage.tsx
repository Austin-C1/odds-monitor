import React, { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import {
  CheckOutlined,
  DeleteOutlined,
  EditOutlined,
  FormOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SendOutlined,
} from '@ant-design/icons'
import TextArea from 'antd/es/input/TextArea'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { TelegramConfigForm } from '../components/notifications'
import { apiService } from '../services/api'
import type {
  NotificationConfig,
  NotificationConfigRequest,
  NotificationConfigUpdateRequest,
  NotificationTemplate,
  TemplateTypeInfo,
  TemplateVariable,
  TemplateVariablesResponse,
} from '../types'
import { extractApiErrorMessage } from '../utils/apiError'
import {
  extractTelegramConfig,
  isTelegramConfigReadyForTest,
  normalizeChatIds,
} from './notificationSettingsHelpers'

const { Paragraph, Text, Title } = Typography

const variableTagStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  cursor: 'pointer',
  marginBottom: 6,
  marginRight: 6,
  borderRadius: 6,
  padding: '4px 10px',
  fontSize: 12,
  transition: 'all 0.2s ease',
  border: '1px solid #e8e8e8',
  background: '#ffffff',
  color: 'rgba(0, 0, 0, 0.65)',
}

const variableTagHoverStyle: React.CSSProperties = {
  borderColor: '#1890ff',
  background: '#e6f7ff',
  color: '#1890ff',
  transform: 'translateY(-1px)',
  boxShadow: '0 2px 4px rgba(24, 144, 255, 0.2)',
}

const CATEGORY_LABELS: Record<string, string> = {
  common: 'notificationSettings.templates.commonVariables',
  order: 'notificationSettings.templates.orderVariables',
  copy_trading: 'notificationSettings.templates.copyTradingVariables',
  monitor: 'notificationSettings.templates.monitorVariables',
  redeem: 'notificationSettings.templates.redeemVariables',
  error: 'notificationSettings.templates.errorVariables',
  filter: 'notificationSettings.templates.filterVariables',
  strategy: 'notificationSettings.templates.strategyVariables',
}

const TEST_NOTIFICATION_MESSAGE = '这是一条测试消息'

type RobotConfigOverrides = {
  monitorModeEnabled?: boolean
  liveOnlyModeEnabled?: boolean
  prematchWindowMinutes?: number | null
  handicapCombinedWaterMin?: number | null
  totalCombinedWaterMin?: number | null
  handicapOddsMoveMin?: number | null
  totalOddsMoveMin?: number | null
  moneylineOddsMoveMin?: number | null
}

type WaterLimitDraft = {
  handicapCombinedWaterMin: number | null
  totalCombinedWaterMin: number | null
}

type OddsMoveFilterDraft = {
  handicapOddsMoveMin: number | null
  totalOddsMoveMin: number | null
  moneylineOddsMoveMin: number | null
}

type PrematchWindowDraft = {
  prematchWindowMinutes: number | null
}

const normalizeWaterLimit = (value: unknown): number | null => {
  if (value === null || value === undefined || value === '') {
    return null
  }
  const numericValue = Number(value)
  return Number.isFinite(numericValue) ? numericValue : null
}

const normalizePrematchWindow = (value: unknown): number | null => {
  if (value === null || value === undefined || value === '') {
    return null
  }
  const numericValue = Number(value)
  return Number.isFinite(numericValue) && numericValue > 0 ? Math.floor(numericValue) : null
}

const getWaterLimitDraft = (config: NotificationConfig): WaterLimitDraft => {
  const telegramConfig = extractTelegramConfig(config)
  return {
    handicapCombinedWaterMin: normalizeWaterLimit(telegramConfig.handicapCombinedWaterMin),
    totalCombinedWaterMin: normalizeWaterLimit(telegramConfig.totalCombinedWaterMin),
  }
}

const getOddsMoveFilterDraft = (config: NotificationConfig): OddsMoveFilterDraft => {
  const telegramConfig = extractTelegramConfig(config)
  return {
    handicapOddsMoveMin: normalizeWaterLimit(telegramConfig.handicapOddsMoveMin),
    totalOddsMoveMin: normalizeWaterLimit(telegramConfig.totalOddsMoveMin),
    moneylineOddsMoveMin: normalizeWaterLimit(telegramConfig.moneylineOddsMoveMin),
  }
}

const getPrematchWindowDraft = (config: NotificationConfig): PrematchWindowDraft => {
  const telegramConfig = extractTelegramConfig(config)
  return {
    prematchWindowMinutes: normalizePrematchWindow(telegramConfig.prematchWindowMinutes),
  }
}

const NotificationSettingsPage: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [form] = Form.useForm()
  const [configs, setConfigs] = useState<NotificationConfig[]>([])
  const [waterLimitDrafts, setWaterLimitDrafts] = useState<Record<number, WaterLimitDraft>>({})
  const [waterLimitModalConfig, setWaterLimitModalConfig] = useState<NotificationConfig | null>(null)
  const [oddsMoveFilterDrafts, setOddsMoveFilterDrafts] = useState<Record<number, OddsMoveFilterDraft>>({})
  const [oddsMoveFilterModalConfig, setOddsMoveFilterModalConfig] = useState<NotificationConfig | null>(null)
  const [prematchWindowDrafts, setPrematchWindowDrafts] = useState<Record<number, PrematchWindowDraft>>({})
  const [prematchWindowModalConfig, setPrematchWindowModalConfig] = useState<NotificationConfig | null>(null)
  const [loading, setLoading] = useState(false)
  const [modalVisible, setModalVisible] = useState(false)
  const [editingConfig, setEditingConfig] = useState<NotificationConfig | null>(null)
  const [testLoading, setTestLoading] = useState(false)
  const [templateTypes, setTemplateTypes] = useState<TemplateTypeInfo[]>([])
  const [selectedTemplateType, setSelectedTemplateType] = useState<string>('ORDER_SUCCESS')
  const [currentTemplate, setCurrentTemplate] = useState<NotificationTemplate | null>(null)
  const [templateVariables, setTemplateVariables] = useState<TemplateVariablesResponse | null>(null)
  const [templateContent, setTemplateContent] = useState('')
  const [testTemplateLoading, setTestTemplateLoading] = useState(false)
  const [variableHoverKey, setVariableHoverKey] = useState<string | null>(null)

  const showApiError = useCallback((error: unknown, fallback: string) => {
    message.error(extractApiErrorMessage(error, fallback))
  }, [])

  const getConfigChatIds = useCallback((config: NotificationConfig) => {
    return normalizeChatIds(extractTelegramConfig(config).chatIds)
  }, [])

  const getMonitorModeEnabled = useCallback((config: NotificationConfig) => {
    return Boolean(extractTelegramConfig(config).monitorModeEnabled)
  }, [])

  const getLiveOnlyModeEnabled = useCallback((config: NotificationConfig) => {
    return Boolean(extractTelegramConfig(config).liveOnlyModeEnabled)
  }, [])

  const isConfigReadyForTest = useCallback((config: NotificationConfig) => isTelegramConfigReadyForTest(config), [])

  const readyTestConfigs = configs.filter(isConfigReadyForTest)
  const hasReadyTestConfig = readyTestConfigs.length > 0

  const fetchConfigs = useCallback(async () => {
    setLoading(true)
    try {
      const response = await apiService.notifications.list({ type: 'telegram' })
      if (response.data.code === 0 && response.data.data) {
        const nextConfigs = response.data.data
        const nextDrafts: Record<number, WaterLimitDraft> = {}
        const nextMoveDrafts: Record<number, OddsMoveFilterDraft> = {}
        const nextPrematchDrafts: Record<number, PrematchWindowDraft> = {}
        nextConfigs.forEach((config) => {
          if (config.id) {
            nextDrafts[config.id] = getWaterLimitDraft(config)
            nextMoveDrafts[config.id] = getOddsMoveFilterDraft(config)
            nextPrematchDrafts[config.id] = getPrematchWindowDraft(config)
          }
        })
        setConfigs(nextConfigs)
        setWaterLimitDrafts(nextDrafts)
        setOddsMoveFilterDrafts(nextMoveDrafts)
        setPrematchWindowDrafts(nextPrematchDrafts)
      } else {
        message.error(response.data.msg || t('notificationSettings.fetchFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }, [showApiError, t])

  const fetchTemplateTypes = useCallback(async () => {
    try {
      const response = await apiService.notifications.getTemplateTypes()
      if (response.data.code === 0 && response.data.data) {
        setTemplateTypes(response.data.data)
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.templates.fetchTypesFailed'))
    }
  }, [showApiError, t])

  const fetchTemplateDetail = useCallback(async (templateType: string) => {
    try {
      const response = await apiService.notifications.getTemplateDetail({ templateType })
      if (response.data.code === 0 && response.data.data) {
        setCurrentTemplate(response.data.data)
        setTemplateContent(response.data.data.templateContent)
      }
    } catch (error) {
      setCurrentTemplate(null)
      setTemplateContent('')
      showApiError(error, t('notificationSettings.templates.fetchDetailFailed'))
    }
  }, [showApiError, t])

  const fetchTemplateVariables = useCallback(async (templateType: string) => {
    try {
      const response = await apiService.notifications.getTemplateVariables({ templateType })
      if (response.data.code === 0 && response.data.data) {
        setTemplateVariables(response.data.data)
      }
    } catch (error) {
      setTemplateVariables(null)
      showApiError(error, t('notificationSettings.templates.fetchVariablesFailed'))
    }
  }, [showApiError, t])

  useEffect(() => {
    fetchConfigs()
    fetchTemplateTypes()
  }, [fetchConfigs, fetchTemplateTypes])

  useEffect(() => {
    if (!selectedTemplateType) {
      return
    }

    fetchTemplateDetail(selectedTemplateType)
    fetchTemplateVariables(selectedTemplateType)
  }, [fetchTemplateDetail, fetchTemplateVariables, selectedTemplateType])

  const handleCreate = () => {
    setEditingConfig(null)
    form.resetFields()
    form.setFieldsValue({
      type: 'telegram',
      enabled: true,
      config: {
        botToken: '',
        chatIds: '',
        monitorModeEnabled: false,
        liveOnlyModeEnabled: false,
        prematchWindowMinutes: null,
        marketBettingQueryEnabled: false,
        handicapCombinedWaterMin: null,
        totalCombinedWaterMin: null,
        handicapOddsMoveMin: null,
        totalOddsMoveMin: null,
        moneylineOddsMoveMin: null,
      },
    })
    setModalVisible(true)
  }

  const handleEdit = (config: NotificationConfig) => {
    setEditingConfig(config)
    const telegramConfig = extractTelegramConfig(config)
    const chatIds = normalizeChatIds(telegramConfig.chatIds).join(',')

    form.setFieldsValue({
      type: config.type,
      name: config.name,
      enabled: config.enabled,
      config: {
        botToken: telegramConfig.botToken || '',
        chatIds,
        monitorModeEnabled: Boolean(telegramConfig.monitorModeEnabled),
        liveOnlyModeEnabled: Boolean(telegramConfig.liveOnlyModeEnabled),
        prematchWindowMinutes: normalizePrematchWindow(telegramConfig.prematchWindowMinutes),
        marketBettingQueryEnabled: Boolean(telegramConfig.marketBettingQueryEnabled),
        handicapCombinedWaterMin: normalizeWaterLimit(telegramConfig.handicapCombinedWaterMin),
        totalCombinedWaterMin: normalizeWaterLimit(telegramConfig.totalCombinedWaterMin),
        handicapOddsMoveMin: normalizeWaterLimit(telegramConfig.handicapOddsMoveMin),
        totalOddsMoveMin: normalizeWaterLimit(telegramConfig.totalOddsMoveMin),
        moneylineOddsMoveMin: normalizeWaterLimit(telegramConfig.moneylineOddsMoveMin),
      },
    })
    setModalVisible(true)
  }

  const buildConfigPayload = (
    config: NotificationConfig,
    overrides: RobotConfigOverrides = {}
  ): NotificationConfigUpdateRequest => {
    const telegramConfig = extractTelegramConfig(config)

    return {
      id: config.id!,
      type: config.type,
      name: config.name,
      enabled: config.enabled,
      config: {
        botToken: telegramConfig.botToken || '',
        chatIds: normalizeChatIds(telegramConfig.chatIds),
        monitorModeEnabled: overrides.monitorModeEnabled ?? Boolean(telegramConfig.monitorModeEnabled),
        liveOnlyModeEnabled: overrides.liveOnlyModeEnabled ?? Boolean(telegramConfig.liveOnlyModeEnabled),
        prematchWindowMinutes: overrides.prematchWindowMinutes ?? normalizePrematchWindow(telegramConfig.prematchWindowMinutes),
        marketBettingQueryEnabled: Boolean(telegramConfig.marketBettingQueryEnabled),
        marketBettingDailyReportEnabled: Boolean(telegramConfig.marketBettingDailyReportEnabled),
        marketBettingDailyReportTime: telegramConfig.marketBettingDailyReportTime || '02:00',
        handicapCombinedWaterMin: overrides.handicapCombinedWaterMin ?? normalizeWaterLimit(telegramConfig.handicapCombinedWaterMin),
        totalCombinedWaterMin: overrides.totalCombinedWaterMin ?? normalizeWaterLimit(telegramConfig.totalCombinedWaterMin),
        handicapOddsMoveMin: overrides.handicapOddsMoveMin ?? normalizeWaterLimit(telegramConfig.handicapOddsMoveMin),
        totalOddsMoveMin: overrides.totalOddsMoveMin ?? normalizeWaterLimit(telegramConfig.totalOddsMoveMin),
        moneylineOddsMoveMin: overrides.moneylineOddsMoveMin ?? normalizeWaterLimit(telegramConfig.moneylineOddsMoveMin),
        copyTradingLeaderGroups: telegramConfig.copyTradingLeaderGroups || [],
        copyTradingCategories: telegramConfig.copyTradingCategories || [],
        copyTradingNotificationTypes: telegramConfig.copyTradingNotificationTypes || [],
      },
    }
  }

  const handleDelete = async (id: number) => {
    try {
      const response = await apiService.notifications.delete({ id })
      if (response.data.code === 0) {
        message.success(t('notificationSettings.deleteSuccess'))
        fetchConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.deleteFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.deleteFailed'))
    }
  }

  const handleUpdateEnabled = async (id: number, enabled: boolean) => {
    try {
      const response = await apiService.notifications.updateEnabled({ id, enabled })
      if (response.data.code === 0) {
        message.success(enabled ? t('notificationSettings.enableSuccess') : t('notificationSettings.disableSuccess'))
        fetchConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.updateStatusFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.updateStatusFailed'))
    }
  }

  const handleToggleMonitorMode = async (config: NotificationConfig, monitorModeEnabled: boolean) => {
    try {
      const response = await apiService.notifications.update(buildConfigPayload(config, { monitorModeEnabled }))
      if (response.data.code === 0) {
        message.success(
          monitorModeEnabled
            ? t('notificationSettings.monitorModeEnableSuccess')
            : t('notificationSettings.monitorModeDisableSuccess')
        )
        fetchConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.monitorModeUpdateFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.monitorModeUpdateFailed'))
    }
  }

  const handleToggleLiveOnlyMode = async (config: NotificationConfig, liveOnlyModeEnabled: boolean) => {
    try {
      const response = await apiService.notifications.update(buildConfigPayload(config, { liveOnlyModeEnabled }))
      if (response.data.code === 0) {
        message.success(liveOnlyModeEnabled ? '已切换为滚球模式' : '已切换为赛前模式')
        fetchConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.updateFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.updateFailed'))
    }
  }

  const openWaterLimitModal = (config: NotificationConfig) => {
    if (config.id) {
      setWaterLimitDrafts((current) => ({
        ...current,
        [config.id!]: current[config.id!] ?? getWaterLimitDraft(config),
      }))
    }
    setWaterLimitModalConfig(config)
  }

  const handleUpdateWaterLimits = async (config: NotificationConfig) => {
    const draft = config.id ? waterLimitDrafts[config.id] : undefined
    const overrides: RobotConfigOverrides = {
      handicapCombinedWaterMin: normalizeWaterLimit(draft?.handicapCombinedWaterMin),
      totalCombinedWaterMin: normalizeWaterLimit(draft?.totalCombinedWaterMin),
    }
    try {
      const response = await apiService.notifications.update(buildConfigPayload(config, overrides))
      if (response.data.code === 0) {
        message.success(t('notificationSettings.updateSuccess'))
        setWaterLimitModalConfig(null)
        fetchConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.updateFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.updateFailed'))
      fetchConfigs()
    }
  }

  const openOddsMoveFilterModal = (config: NotificationConfig) => {
    if (config.id) {
      setOddsMoveFilterDrafts((current) => ({
        ...current,
        [config.id!]: current[config.id!] ?? getOddsMoveFilterDraft(config),
      }))
    }
    setOddsMoveFilterModalConfig(config)
  }

  const handleUpdateOddsMoveFilter = async (config: NotificationConfig) => {
    const draft = config.id ? oddsMoveFilterDrafts[config.id] : undefined
    const overrides: RobotConfigOverrides = {
      handicapOddsMoveMin: normalizeWaterLimit(draft?.handicapOddsMoveMin),
      totalOddsMoveMin: normalizeWaterLimit(draft?.totalOddsMoveMin),
      moneylineOddsMoveMin: normalizeWaterLimit(draft?.moneylineOddsMoveMin),
    }
    try {
      const response = await apiService.notifications.update(buildConfigPayload(config, overrides))
      if (response.data.code === 0) {
        message.success(t('notificationSettings.updateSuccess'))
        setOddsMoveFilterModalConfig(null)
        fetchConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.updateFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.updateFailed'))
      fetchConfigs()
    }
  }

  const openPrematchWindowModal = (config: NotificationConfig) => {
    if (config.id) {
      setPrematchWindowDrafts((current) => ({
        ...current,
        [config.id!]: current[config.id!] ?? getPrematchWindowDraft(config),
      }))
    }
    setPrematchWindowModalConfig(config)
  }

  const handleUpdatePrematchWindow = async (config: NotificationConfig) => {
    const draft = config.id ? prematchWindowDrafts[config.id] : undefined
    const overrides: RobotConfigOverrides = {
      prematchWindowMinutes: normalizePrematchWindow(draft?.prematchWindowMinutes),
    }
    try {
      const response = await apiService.notifications.update(buildConfigPayload(config, overrides))
      if (response.data.code === 0) {
        message.success(t('notificationSettings.updateSuccess'))
        setPrematchWindowModalConfig(null)
        fetchConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.updateFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.updateFailed'))
      fetchConfigs()
    }
  }

  const handleTestConfig = async (config: NotificationConfig) => {
    if (!isConfigReadyForTest(config)) {
      message.warning(t('notificationSettings.testUnavailable'))
      return
    }

    setTestLoading(true)
    try {
      const response = await apiService.notifications.test({
        configId: config.id,
        message: TEST_NOTIFICATION_MESSAGE,
      })
      if (response.data.code === 0 && response.data.data) {
        message.success(t('notificationSettings.testSuccess'))
      } else {
        message.error(response.data.msg || t('notificationSettings.testFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.testFailed'))
    } finally {
      setTestLoading(false)
    }
  }

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      const configData: NotificationConfigRequest | NotificationConfigUpdateRequest = {
        type: values.type,
        name: values.name,
        enabled: values.enabled,
        config: {
          botToken: values.config.botToken,
          chatIds: normalizeChatIds(values.config.chatIds),
          monitorModeEnabled: Boolean(values.config.monitorModeEnabled),
          liveOnlyModeEnabled: Boolean(values.config.liveOnlyModeEnabled),
          prematchWindowMinutes: normalizePrematchWindow(values.config.prematchWindowMinutes),
          marketBettingQueryEnabled: editingConfig ? Boolean(extractTelegramConfig(editingConfig).marketBettingQueryEnabled) : false,
          marketBettingDailyReportEnabled: editingConfig ? Boolean(extractTelegramConfig(editingConfig).marketBettingDailyReportEnabled) : false,
          marketBettingDailyReportTime: editingConfig ? extractTelegramConfig(editingConfig).marketBettingDailyReportTime || '02:00' : '02:00',
          handicapCombinedWaterMin: normalizeWaterLimit(values.config.handicapCombinedWaterMin),
          totalCombinedWaterMin: normalizeWaterLimit(values.config.totalCombinedWaterMin),
          handicapOddsMoveMin: normalizeWaterLimit(values.config.handicapOddsMoveMin),
          totalOddsMoveMin: normalizeWaterLimit(values.config.totalOddsMoveMin),
          moneylineOddsMoveMin: normalizeWaterLimit(values.config.moneylineOddsMoveMin),
          copyTradingLeaderGroups: editingConfig ? extractTelegramConfig(editingConfig).copyTradingLeaderGroups || [] : [],
          copyTradingCategories: editingConfig ? extractTelegramConfig(editingConfig).copyTradingCategories || [] : [],
          copyTradingNotificationTypes: editingConfig ? extractTelegramConfig(editingConfig).copyTradingNotificationTypes || [] : [],
        },
      }

      if (editingConfig?.id) {
        const response = await apiService.notifications.update({
          ...(configData as NotificationConfigRequest),
          id: editingConfig.id,
        })
        if (response.data.code === 0) {
          message.success(t('notificationSettings.updateSuccess'))
          setModalVisible(false)
          fetchConfigs()
        } else {
          message.error(response.data.msg || t('notificationSettings.updateFailed'))
        }
      } else {
        const response = await apiService.notifications.create(configData as NotificationConfigRequest)
        if (response.data.code === 0) {
          message.success(t('notificationSettings.createSuccess'))
          setModalVisible(false)
          fetchConfigs()
        } else {
          message.error(response.data.msg || t('notificationSettings.createFailed'))
        }
      }
    } catch (error: any) {
      if (error?.errorFields) {
        return
      }
      showApiError(error, t('message.error'))
    }
  }

  const handleSaveTemplate = async () => {
    try {
      const response = await apiService.notifications.updateTemplate({
        templateType: selectedTemplateType,
        templateContent,
      })
      if (response.data.code === 0) {
        message.success(t('notificationSettings.templates.saveSuccess'))
        fetchTemplateDetail(selectedTemplateType)
      } else {
        message.error(response.data.msg || t('notificationSettings.templates.saveFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.templates.saveFailed'))
    }
  }

  const handleResetTemplate = async () => {
    try {
      const response = await apiService.notifications.resetTemplate({
        templateType: selectedTemplateType,
      })
      if (response.data.code === 0) {
        message.success(t('notificationSettings.templates.resetSuccess'))
        fetchTemplateDetail(selectedTemplateType)
      } else {
        message.error(response.data.msg || t('notificationSettings.templates.resetFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.templates.resetFailed'))
    }
  }

  const handleTestTemplate = async () => {
    if (!hasReadyTestConfig) {
      message.warning(t('notificationSettings.testUnavailable'))
      return
    }

    setTestTemplateLoading(true)
    try {
      const response = await apiService.notifications.testTemplate({
        templateType: selectedTemplateType,
        templateContent,
      })
      if (response.data.code === 0 && response.data.data) {
        message.success(t('notificationSettings.templates.testSuccess'))
      } else {
        message.error(response.data.msg || t('notificationSettings.templates.testFailed'))
      }
    } catch (error) {
      showApiError(error, t('notificationSettings.templates.testFailed'))
    } finally {
      setTestTemplateLoading(false)
    }
  }

  const handleCopyVariable = useCallback((variable: string) => {
    const text = `{{${variable}}}`

    const fallbackCopy = (value: string) => {
      const textArea = document.createElement('textarea')
      textArea.value = value
      textArea.style.position = 'fixed'
      textArea.style.left = '-9999px'
      textArea.style.top = '-9999px'
      document.body.appendChild(textArea)
      textArea.focus()
      textArea.select()

      try {
        document.execCommand('copy')
        message.success(t('notificationSettings.templates.copied'))
      } catch {
        message.error(t('common.copyFailed'))
      } finally {
        document.body.removeChild(textArea)
      }
    }

    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard
        .writeText(text)
        .then(() => {
          message.success(t('notificationSettings.templates.copied'))
        })
        .catch(() => fallbackCopy(text))
      return
    }

    fallbackCopy(text)
  }, [t])

  const renderVariableItem = (variable: TemplateVariable) => {
    const isHover = variableHoverKey === variable.key
    const label = t(`notificationSettings.templates.variableLabels.${variable.key}`)
    const description = t(`notificationSettings.templates.variableDescriptions.${variable.key}`)

    const content = (
      <span
        role="button"
        tabIndex={0}
        style={{ ...variableTagStyle, ...(isHover && !isMobile ? variableTagHoverStyle : {}) }}
        onClick={() => handleCopyVariable(variable.key)}
        onMouseEnter={() => !isMobile && setVariableHoverKey(variable.key)}
        onMouseLeave={() => !isMobile && setVariableHoverKey(null)}
        onKeyDown={(event) => event.key === 'Enter' && handleCopyVariable(variable.key)}
      >
        <span style={{ fontFamily: 'monospace' }}>{label}</span>
      </span>
    )

    if (isMobile) {
      return (
        <span key={variable.key} style={{ display: 'inline-block' }}>
          {content}
        </span>
      )
    }

    return (
      <Tooltip key={variable.key} title={description || `{{${variable.key}}}`} placement="top">
        {content}
      </Tooltip>
    )
  }

  const renderVariablesPanel = () => {
    if (!templateVariables) {
      return null
    }

    return (
      <Card
        size="small"
        title={<span style={{ fontSize: 13, fontWeight: 500 }}>{t('notificationSettings.templates.variables')}</span>}
        style={{ height: '100%', borderRadius: 8 }}
        styles={{ body: { padding: '12px 16px', maxHeight: 420, overflowY: 'auto' } }}
      >
        {templateVariables.categories.map((category) => {
          const categoryVariables = templateVariables.variables.filter((item) => item.category === category.key)
          if (categoryVariables.length === 0) {
            return null
          }

          return (
            <div key={category.key} style={{ marginBottom: 16 }}>
              <Text type="secondary" style={{ marginBottom: 8, display: 'block', fontSize: 12 }}>
                {t(CATEGORY_LABELS[category.key])}
              </Text>
              <div style={{ display: 'flex', flexWrap: 'wrap' }}>
                {categoryVariables.sort((a, b) => a.sortOrder - b.sortOrder).map(renderVariableItem)}
              </div>
            </div>
          )
        })}
        <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0, fontSize: 11, textAlign: 'center' }}>
          {t('notificationSettings.templates.clickToCopy')}
        </Paragraph>
      </Card>
    )
  }

  const templateTypeTabItems = templateTypes.map((type) => ({
    key: type.type,
    label: (
      <Tooltip title={t(`notificationSettings.templateTypeDescriptions.${type.type}`)} placement="top">
        <span>{t(`notificationSettings.templateTypes.${type.type}`)}</span>
      </Tooltip>
    ),
  }))

  const renderWaterLimitControls = (record: NotificationConfig) => {
    const draft = record.id ? waterLimitDrafts[record.id] ?? getWaterLimitDraft(record) : getWaterLimitDraft(record)
    const updateDraft = (field: keyof WaterLimitDraft, value: number | null) => {
      if (!record.id) return
      setWaterLimitDrafts((current) => ({
        ...current,
        [record.id!]: {
          ...draft,
          [field]: normalizeWaterLimit(value),
        },
      }))
    }

    return (
      <Space direction="vertical" size={6}>
        <Space size={6} wrap>
          <Text style={{ width: 90 }}>让球合水 ≥</Text>
          <InputNumber
            min={0}
            max={2}
            step={0.01}
            precision={2}
            value={draft.handicapCombinedWaterMin}
            placeholder="不限"
            onChange={(value) => updateDraft('handicapCombinedWaterMin', value)}
            style={{ width: 160 }}
          />
        </Space>
        <Space size={6} wrap>
          <Text style={{ width: 90 }}>大小球合水 ≥</Text>
          <InputNumber
            min={0}
            max={2}
            step={0.01}
            precision={2}
            value={draft.totalCombinedWaterMin}
            placeholder="不限"
            onChange={(value) => updateDraft('totalCombinedWaterMin', value)}
            style={{ width: 160 }}
          />
        </Space>
        <Text type="secondary" style={{ fontSize: 12 }}>
          开启监控模式后生效，Polymarket 独立通道不受合水限制。
        </Text>
      </Space>
    )
  }

  const formatWaterLimitSummary = (record: NotificationConfig) => {
    const draft = getWaterLimitDraft(record)
    const handicap = draft.handicapCombinedWaterMin ?? '不限'
    const total = draft.totalCombinedWaterMin ?? '不限'
    return `合水限制：让球 ${handicap} / 大小球 ${total}`
  }

  const renderOddsMoveFilterControls = (record: NotificationConfig) => {
    const draft = record.id ? oddsMoveFilterDrafts[record.id] ?? getOddsMoveFilterDraft(record) : getOddsMoveFilterDraft(record)
    const updateDraft = (field: keyof OddsMoveFilterDraft, value: number | null) => {
      if (!record.id) return
      setOddsMoveFilterDrafts((current) => ({
        ...current,
        [record.id!]: {
          ...draft,
          [field]: normalizeWaterLimit(value),
        },
      }))
    }

    return (
      <Space direction="vertical" size={8}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          只推送水位变化达到阈值的赔率变动；留空表示不筛选。
        </Text>
        <Space size={6} wrap>
          <Text style={{ width: 110 }}>让球动水 ≥</Text>
          <InputNumber min={0} max={2} step={0.01} precision={2} value={draft.handicapOddsMoveMin} placeholder="不限" onChange={(value) => updateDraft('handicapOddsMoveMin', value)} style={{ width: 160 }} />
        </Space>
        <Space size={6} wrap>
          <Text style={{ width: 110 }}>大小球动水 ≥</Text>
          <InputNumber min={0} max={2} step={0.01} precision={2} value={draft.totalOddsMoveMin} placeholder="不限" onChange={(value) => updateDraft('totalOddsMoveMin', value)} style={{ width: 160 }} />
        </Space>
        <Space size={6} wrap>
          <Text style={{ width: 110 }}>胜平负动水 ≥</Text>
          <InputNumber min={0} max={2} step={0.01} precision={2} value={draft.moneylineOddsMoveMin} placeholder="不限" onChange={(value) => updateDraft('moneylineOddsMoveMin', value)} style={{ width: 160 }} />
        </Space>
      </Space>
    )
  }

  const formatOddsMoveFilterSummary = (record: NotificationConfig) => {
    const draft = getOddsMoveFilterDraft(record)
    const handicap = draft.handicapOddsMoveMin ?? '不限'
    const total = draft.totalOddsMoveMin ?? '不限'
    const moneyline = draft.moneylineOddsMoveMin ?? '不限'
    return `动水限制：让球 ${handicap} / 大小球 ${total} / 胜平负 ${moneyline}`
  }

  const renderPrematchWindowControls = (record: NotificationConfig) => {
    const draft = record.id ? prematchWindowDrafts[record.id] ?? getPrematchWindowDraft(record) : getPrematchWindowDraft(record)
    const updateDraft = (value: number | null) => {
      if (!record.id) return
      setPrematchWindowDrafts((current) => ({
        ...current,
        [record.id!]: {
          prematchWindowMinutes: normalizePrematchWindow(value),
        },
      }))
    }

    return (
      <Space direction="vertical" size={8}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          只在赛前模式生效；不填表示不限制赛前比赛时间。比赛开赛后由滚球模式处理。
        </Text>
        <Space size={6} wrap>
          <Text style={{ width: 110 }}>开赛前分钟 ≤</Text>
          <InputNumber
            min={1}
            max={10080}
            step={1}
            precision={0}
            value={draft.prematchWindowMinutes}
            placeholder="不限"
            onChange={(value) => updateDraft(value)}
            style={{ width: 160 }}
          />
        </Space>
      </Space>
    )
  }

  const formatPrematchWindowSummary = (record: NotificationConfig) => {
    const minutes = getPrematchWindowDraft(record).prematchWindowMinutes
    return `赛前区间：${minutes ? `${minutes} 分钟` : '不限'}`
  }

  const configColumns = [
    {
      title: t('notificationSettings.configName'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('notificationSettings.type'),
      dataIndex: 'type',
      key: 'type',
      render: (type: string) => <Tag color="blue">{type.toUpperCase()}</Tag>,
    },
    {
      title: t('notificationSettings.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      render: (enabled: boolean) => (
        <Tag color={enabled ? 'green' : 'default'}>
          {enabled ? t('notificationSettings.enabledStatus') : t('notificationSettings.disabledStatus')}
        </Tag>
      ),
    },
    {
      title: t('notificationSettings.monitorMode'),
      key: 'monitorMode',
      render: (_: unknown, record: NotificationConfig) => (
        <Space size="small" wrap>
          <Space direction="vertical" size={2}>
            <Tooltip title={t('notificationSettings.monitorModeDescription')}>
              <Switch
                checked={getMonitorModeEnabled(record)}
                size="small"
                onChange={(checked) => handleToggleMonitorMode(record, checked)}
              />
            </Tooltip>
            <Space size={6}>
              <Switch
                checked={getLiveOnlyModeEnabled(record)}
                size="small"
                disabled={!getMonitorModeEnabled(record)}
                onChange={(checked) => handleToggleLiveOnlyMode(record, checked)}
              />
            </Space>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {formatWaterLimitSummary(record)}
            </Text>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {formatOddsMoveFilterSummary(record)}
            </Text>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {formatPrematchWindowSummary(record)}
            </Text>
          </Space>
          <Button size="small" onClick={() => openWaterLimitModal(record)}>
            {formatWaterLimitSummary(record)}
          </Button>
          <Button size="small" onClick={() => openOddsMoveFilterModal(record)}>
            {formatOddsMoveFilterSummary(record)}
          </Button>
          <Button size="small" onClick={() => openPrematchWindowModal(record)}>
            {formatPrematchWindowSummary(record)}
          </Button>
        </Space>
      ),
    },
    {
      title: t('notificationSettings.chatIds'),
      key: 'chatIds',
      render: (_: unknown, record: NotificationConfig) => {
        const chatIds = getConfigChatIds(record)
        return chatIds.length > 0 ? (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {chatIds.join(', ')}
          </Text>
        ) : (
          <Text type="danger" style={{ fontSize: 12 }}>
            {t('notificationSettings.chatIdsNotConfigured')}
          </Text>
        )
      },
    },
    {
      title: t('common.actions'),
      key: 'action',
      width: isMobile ? 140 : 220,
      render: (_: unknown, record: NotificationConfig) => (
        <Space size="small" wrap>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            {t('notificationSettings.edit')}
          </Button>
          <Switch checked={record.enabled} size="small" onChange={(checked) => handleUpdateEnabled(record.id!, checked)} />
          <Tooltip title={!isConfigReadyForTest(record) ? t('notificationSettings.testUnavailable') : undefined}>
            <span>
              <Button
                type="link"
                size="small"
                icon={<SendOutlined />}
                loading={testLoading}
                disabled={!isConfigReadyForTest(record)}
                onClick={() => handleTestConfig(record)}
              >
                {t('notificationSettings.test')}
              </Button>
            </span>
          </Tooltip>
          <Popconfirm
            title={t('notificationSettings.deleteConfirm')}
            onConfirm={() => handleDelete(record.id!)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button type="link" danger size="small" icon={<DeleteOutlined />}>
              {t('notificationSettings.delete')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Title level={2} style={{ margin: 0 }}>
          {t('notificationSettings.title')}
        </Title>
      </div>

      <Card
        title={
          <Space>
            <RobotOutlined />
            <span>{t('notificationSettings.botConfig')}</span>
          </Space>
        }
        style={{ marginBottom: 16 }}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
            {t('notificationSettings.addConfig')}
          </Button>
        }
      >
        <Table
          columns={configColumns}
          dataSource={configs}
          loading={loading}
          rowKey="id"
          pagination={false}
          scroll={{ x: isMobile ? 760 : 'auto' }}
        />
      </Card>

      <Card
        title={
          <Space>
            <FormOutlined />
            <span>{t('notificationSettings.templateConfig')}</span>
          </Space>
        }
      >
        <Tabs
          activeKey={selectedTemplateType}
          onChange={setSelectedTemplateType}
          items={templateTypeTabItems}
          style={{ marginBottom: 16 }}
          tabBarStyle={{ marginBottom: 0 }}
          type={isMobile ? 'line' : 'card'}
          size={isMobile ? 'small' : 'middle'}
        />

        <Row gutter={[16, 16]}>
          <Col xs={24} sm={24} md={17}>
            <Card
              size="small"
              variant="borderless"
              style={{ background: '#fafafa', marginBottom: 12, borderRadius: 8 }}
              styles={{ body: { padding: '10px 16px' } }}
            >
              <div
                style={{
                  display: 'flex',
                  flexWrap: 'wrap',
                  gap: 8,
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <Space wrap size="small">
                  <Text type="secondary" style={{ fontSize: 13 }}>
                    {t('notificationSettings.templates.templateContent')}
                  </Text>
                  {currentTemplate && (
                    <Tag color={currentTemplate.isDefault ? 'green' : 'blue'} style={{ margin: 0 }}>
                      {currentTemplate.isDefault
                        ? t('notificationSettings.templates.isDefault')
                        : t('notificationSettings.templates.isCustom')}
                    </Tag>
                  )}
                </Space>
                <Space wrap size="small">
                  <Popconfirm
                    title={t('notificationSettings.templates.resetConfirm')}
                    onConfirm={handleResetTemplate}
                    okText={t('common.confirm')}
                    cancelText={t('common.cancel')}
                  >
                    <Button size="small" icon={<ReloadOutlined />}>
                      {t('notificationSettings.templates.resetToDefault')}
                    </Button>
                  </Popconfirm>
                  <Button size="small" type="primary" icon={<CheckOutlined />} onClick={handleSaveTemplate}>
                    {t('common.save')}
                  </Button>
                  <Tooltip title={!hasReadyTestConfig ? t('notificationSettings.testUnavailable') : undefined}>
                    <span>
                      <Button
                        size="small"
                        icon={<SendOutlined />}
                        loading={testTemplateLoading}
                        disabled={!hasReadyTestConfig}
                        onClick={handleTestTemplate}
                      >
                        {t('notificationSettings.test')}
                      </Button>
                    </span>
                  </Tooltip>
                </Space>
              </div>
            </Card>
            <TextArea
              value={templateContent}
              onChange={(event) => setTemplateContent(event.target.value)}
              rows={isMobile ? 15 : 16}
              style={{ fontFamily: 'monospace', fontSize: 13, borderRadius: 8, resize: 'none' }}
              placeholder={t('notificationSettings.templates.contentPlaceholder')}
            />
          </Col>
          <Col xs={24} sm={24} md={7}>
            {renderVariablesPanel()}
          </Col>
        </Row>
      </Card>

      <Modal
        title={editingConfig ? t('notificationSettings.editConfig') : t('notificationSettings.addConfig')}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        width={isMobile ? '90%' : 600}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="type"
            label={t('notificationSettings.type')}
            rules={[{ required: true, message: t('notificationSettings.typeRequired') }]}
          >
            <Input disabled value="telegram" />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('notificationSettings.configName')}
            rules={[{ required: true, message: t('notificationSettings.configNameRequired') }]}
          >
            <Input placeholder={t('notificationSettings.configNamePlaceholder')} />
          </Form.Item>
          <Form.Item name="enabled" label={t('notificationSettings.enabled')} valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name={['config', 'monitorModeEnabled']} hidden>
            <Input />
          </Form.Item>
          <Form.Item name={['config', 'liveOnlyModeEnabled']} hidden>
            <Input />
          </Form.Item>
          <Form.Item name={['config', 'prematchWindowMinutes']} hidden>
            <InputNumber />
          </Form.Item>
          <Form.Item name={['config', 'handicapCombinedWaterMin']} hidden>
            <InputNumber />
          </Form.Item>
          <Form.Item name={['config', 'totalCombinedWaterMin']} hidden>
            <InputNumber />
          </Form.Item>
          <Form.Item name={['config', 'handicapOddsMoveMin']} hidden>
            <InputNumber />
          </Form.Item>
          <Form.Item name={['config', 'totalOddsMoveMin']} hidden>
            <InputNumber />
          </Form.Item>
          <Form.Item name={['config', 'moneylineOddsMoveMin']} hidden>
            <InputNumber />
          </Form.Item>
          <Form.Item shouldUpdate={(prevValues, currentValues) => prevValues.type !== currentValues.type}>
            {() => {
              const currentType = form.getFieldValue('type') || 'telegram'
              if (currentType !== 'telegram') {
                return null
              }
              return <TelegramConfigForm form={form} />
            }}
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="水位限制"
        open={Boolean(waterLimitModalConfig)}
        onOk={() => waterLimitModalConfig && void handleUpdateWaterLimits(waterLimitModalConfig)}
        onCancel={() => setWaterLimitModalConfig(null)}
        width={isMobile ? '90%' : 420}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
      >
        {waterLimitModalConfig && renderWaterLimitControls(waterLimitModalConfig)}
      </Modal>

      <Modal
        title="动水筛选"
        open={Boolean(oddsMoveFilterModalConfig)}
        onOk={() => oddsMoveFilterModalConfig && void handleUpdateOddsMoveFilter(oddsMoveFilterModalConfig)}
        onCancel={() => setOddsMoveFilterModalConfig(null)}
        width={isMobile ? '90%' : 460}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
      >
        {oddsMoveFilterModalConfig && renderOddsMoveFilterControls(oddsMoveFilterModalConfig)}
      </Modal>
      <Modal
        title="赛前区间盯梢"
        open={Boolean(prematchWindowModalConfig)}
        onOk={() => prematchWindowModalConfig && void handleUpdatePrematchWindow(prematchWindowModalConfig)}
        onCancel={() => setPrematchWindowModalConfig(null)}
        width={isMobile ? '90%' : 420}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
      >
        {prematchWindowModalConfig && renderPrematchWindowControls(prematchWindowModalConfig)}
      </Modal>
    </div>
  )
}

export default NotificationSettingsPage
