export interface ApiResponse<T> {
  code: number
  data: T | null
  msg: string
}

export interface TelegramNotificationConfig {
  botToken?: string
  chatIds?: string[] | string
  monitorModeEnabled?: boolean
  liveOnlyModeEnabled?: boolean
  testModeEnabled?: boolean
  prematchWindowMinutes?: number | string | null
  handicapCombinedWaterMin?: number | string | null
  totalCombinedWaterMin?: number | string | null
  handicapOddsMoveMin?: number | string | null
  totalOddsMoveMin?: number | string | null
  moneylineOddsMoveMin?: number | string | null
  [key: string]: unknown
}

export interface NotificationConfig {
  id?: number
  type: string
  name: string
  enabled: boolean
  config: TelegramNotificationConfig
  createdAt?: number
  updatedAt?: number
}

export interface NotificationConfigRequest {
  type: string
  name: string
  enabled?: boolean
  config: TelegramNotificationConfig
}

export interface NotificationConfigUpdateRequest {
  id: number
  type: string
  name: string
  enabled?: boolean
  config: TelegramNotificationConfig
}

export interface SystemConfig {
  liveObservationMinutes?: number | string | null
  autoBettingEnabled?: boolean
}

export interface NotificationTemplate {
  id?: number
  templateType: string
  templateContent: string
  isDefault: boolean
  createdAt?: number
  updatedAt?: number
}

export interface TemplateTypeInfo {
  type: string
  name: string
  description: string
}

export interface TemplateVariable {
  key: string
  category: string
  sortOrder: number
}

export interface TemplateVariableCategory {
  key: string
  sortOrder: number
}

export interface TemplateVariablesResponse {
  templateType: string
  categories: TemplateVariableCategory[]
  variables: TemplateVariable[]
}
