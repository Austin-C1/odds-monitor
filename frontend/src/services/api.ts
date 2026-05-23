import axios, { AxiosError, AxiosInstance } from 'axios'
import i18n from '../i18n/config'
import type {
  ApiResponse,
  NotificationConfig,
  NotificationConfigRequest,
  NotificationConfigUpdateRequest,
  NotificationTemplate,
  SystemConfig,
  TemplateTypeInfo,
  TemplateVariablesResponse,
} from '../types'
import { getToken, removeToken, setToken } from '../utils'
import { notifyAuthExpired } from './authSession'

const getBaseURL = (): string => {
  const envApiUrl = import.meta.env.VITE_API_URL
  if (envApiUrl) return `${envApiUrl}/api`

  const localBackendUrl = 'http://127.0.0.1:18000'
  const localFrontendHosts = ['127.0.0.1', 'localhost']
  const isPackagedLocalFrontend = (
    localFrontendHosts.includes(window.location.hostname) &&
    window.location.port === '18881'
  )
  return isPackagedLocalFrontend ? `${localBackendUrl}/api` : '/api'
}

const apiClient: AxiosInstance = axios.create({
  baseURL: getBaseURL(),
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

const getCurrentLanguage = (): string => {
  let savedLanguage = localStorage.getItem('i18n_language')
  if (!savedLanguage) {
    savedLanguage = localStorage.getItem('i18nextLng')
  }

  if (savedLanguage && savedLanguage !== 'auto' && ['zh-CN', 'zh-TW'].includes(savedLanguage)) {
    return savedLanguage
  }

  const currentLang = i18n.language || 'zh-CN'
  if (currentLang.startsWith('zh-CN')) return 'zh-CN'
  if (currentLang.startsWith('zh-TW') || currentLang.startsWith('zh-HK')) return 'zh-TW'
  return 'zh-CN'
}

apiClient.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    config.headers['X-Language'] = getCurrentLanguage()
    return config
  },
  (error) => Promise.reject(error)
)

const handleAuthError = (code: number) => {
  if (code >= 2001 && code < 3000) {
    removeToken()
    notifyAuthExpired()
    if (window.location.pathname !== '/login' && window.location.pathname !== '/reset-password') {
      window.location.href = '/login'
    }
  }
}

apiClient.interceptors.response.use(
  (response) => {
    const newToken = response.headers['x-new-token']
    if (newToken) {
      setToken(newToken)
    }

    const data = response.data as ApiResponse<unknown>
    if (data && data.code !== undefined) {
      handleAuthError(data.code)
    }

    return response
  },
  (error: AxiosError<ApiResponse<unknown>>) => {
    if (error.response?.data?.code !== undefined) {
      handleAuthError(error.response.data.code)
    }
    return Promise.reject(error)
  }
)

export const apiService = {
  auth: {
    localLogin: () =>
      apiClient.post<ApiResponse<{ token: string }>>('/auth/local-login', {}),

    resetPassword: (data: { resetKey: string; username: string; newPassword: string }) =>
      apiClient.post<ApiResponse<void>>('/auth/reset-password', data),

    checkFirstUse: () =>
      apiClient.post<ApiResponse<{ isFirstUse: boolean }>>('/auth/check-first-use', {}),
  },

  notifications: {
    list: (data?: { type?: string }) =>
      apiClient.post<ApiResponse<NotificationConfig[]>>('/system/notifications/configs/list', data || {}),

    detail: (data: { id: number }) =>
      apiClient.post<ApiResponse<NotificationConfig>>('/system/notifications/configs/detail', data),

    create: (data: NotificationConfigRequest) =>
      apiClient.post<ApiResponse<NotificationConfig>>('/system/notifications/configs/create', data),

    update: (data: NotificationConfigUpdateRequest) =>
      apiClient.post<ApiResponse<NotificationConfig>>('/system/notifications/configs/update', data),

    updateEnabled: (data: { id: number; enabled: boolean }) =>
      apiClient.post<ApiResponse<NotificationConfig>>('/system/notifications/configs/update-enabled', data),

    delete: (data: { id: number }) =>
      apiClient.post<ApiResponse<void>>('/system/notifications/configs/delete', data),

    test: (data?: { configId?: number; message?: string }) =>
      apiClient.post<ApiResponse<boolean>>('/system/notifications/test', data || {}),

    getTelegramChatIds: (data: { botToken: string }) =>
      apiClient.post<ApiResponse<string[]>>('/system/notifications/telegram/get-chat-ids', data),

    getTemplateTypes: () =>
      apiClient.post<ApiResponse<TemplateTypeInfo[]>>('/system/notifications/templates/types', {}),

    getTemplates: () =>
      apiClient.post<ApiResponse<NotificationTemplate[]>>('/system/notifications/templates/list', {}),

    getTemplateDetail: (data: { templateType: string }) =>
      apiClient.post<ApiResponse<NotificationTemplate>>('/system/notifications/templates/detail', data),

    getTemplateVariables: (data: { templateType: string }) =>
      apiClient.post<ApiResponse<TemplateVariablesResponse>>('/system/notifications/templates/variables', data),

    updateTemplate: (data: { templateType: string; templateContent: string }) =>
      apiClient.post<ApiResponse<NotificationTemplate>>('/system/notifications/templates/update', data),

    resetTemplate: (data: { templateType: string }) =>
      apiClient.post<ApiResponse<NotificationTemplate>>('/system/notifications/templates/reset', data),

    testTemplate: (data: { templateType: string; templateContent?: string }) =>
      apiClient.post<ApiResponse<boolean>>('/system/notifications/templates/test', data),
  },

  systemConfig: {
    get: () =>
      apiClient.post<ApiResponse<SystemConfig>>('/system/config/get', {}),

    updateLiveObservationMinutes: (data: { liveObservationMinutes?: number | null }) =>
      apiClient.post<ApiResponse<SystemConfig>>('/system/config/live-observation-minutes/update', data),
  },
}

export { apiClient }

export default apiService
