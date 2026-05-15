import type { NotificationTemplate, TemplateTypeInfo, TemplateVariablesResponse } from '../types'

export const DEFAULT_NOTIFICATION_TEMPLATE_TYPE = 'ODDS_PREMATCH_PUSH'

export const FOCUSED_NOTIFICATION_TEMPLATE_TYPES: TemplateTypeInfo[] = [
  {
    type: 'ODDS_PREMATCH_PUSH',
    name: 'Prematch Odds Push',
    description: 'Notification sent when prematch odds change',
  },
  {
    type: 'ODDS_LIVE_PUSH',
    name: 'Live Odds Push',
    description: 'Notification sent when live odds change',
  },
  {
    type: 'BETTING_TEMPLATE',
    name: 'Betting Template',
    description: 'Reserved template slot for future betting notifications',
  },
]

const FOCUSED_TEMPLATE_TYPE_SET = new Set(FOCUSED_NOTIFICATION_TEMPLATE_TYPES.map((type) => type.type))

export const getVisibleNotificationTemplateTypes = (apiTypes: TemplateTypeInfo[] = []): TemplateTypeInfo[] => {
  const apiTypeByKey = new Map(
    apiTypes
      .filter((type) => FOCUSED_TEMPLATE_TYPE_SET.has(type.type))
      .map((type) => [type.type, type])
  )

  return FOCUSED_NOTIFICATION_TEMPLATE_TYPES.map((fallbackType) => apiTypeByKey.get(fallbackType.type) ?? fallbackType)
}

export const getDefaultNotificationTemplate = (templateType: string): NotificationTemplate | null => {
  const templateContent = DEFAULT_NOTIFICATION_TEMPLATE_CONTENT[templateType]
  if (!templateContent) {
    return null
  }

  return {
    templateType,
    templateContent,
    isDefault: true,
  }
}

export const getDefaultNotificationTemplateVariables = (templateType: string): TemplateVariablesResponse | null => {
  const variables = DEFAULT_NOTIFICATION_TEMPLATE_VARIABLES[templateType]
  if (!variables) {
    return null
  }

  const usedCategories = new Set(variables.map((variable) => variable.category))
  return {
    templateType,
    categories: DEFAULT_NOTIFICATION_TEMPLATE_CATEGORIES.filter((category) => usedCategories.has(category.key)),
    variables,
  }
}

const DEFAULT_NOTIFICATION_TEMPLATE_CATEGORIES = [
  { key: 'common', sortOrder: 0 },
  { key: 'monitor', sortOrder: 10 },
  { key: 'betting', sortOrder: 20 },
]

const DEFAULT_NOTIFICATION_TEMPLATE_VARIABLES: Record<string, TemplateVariablesResponse['variables']> = {
  ODDS_PREMATCH_PUSH: [
    { key: 'match_title', category: 'monitor', sortOrder: 10 },
    { key: 'league_name', category: 'monitor', sortOrder: 11 },
    { key: 'market_lines', category: 'monitor', sortOrder: 12 },
    { key: 'filter_summary', category: 'monitor', sortOrder: 13 },
    { key: 'time', category: 'common', sortOrder: 1 },
  ],
  ODDS_LIVE_PUSH: [
    { key: 'match_title', category: 'monitor', sortOrder: 10 },
    { key: 'league_name', category: 'monitor', sortOrder: 11 },
    { key: 'elapsed_minutes', category: 'monitor', sortOrder: 12 },
    { key: 'score_text', category: 'monitor', sortOrder: 13 },
    { key: 'market_lines', category: 'monitor', sortOrder: 14 },
    { key: 'filter_summary', category: 'monitor', sortOrder: 15 },
    { key: 'time', category: 'common', sortOrder: 1 },
  ],
  BETTING_TEMPLATE: [
    { key: 'match_title', category: 'betting', sortOrder: 10 },
    { key: 'league_name', category: 'betting', sortOrder: 11 },
    { key: 'market_title', category: 'betting', sortOrder: 12 },
    { key: 'selection_name', category: 'betting', sortOrder: 13 },
    { key: 'odds', category: 'betting', sortOrder: 14 },
    { key: 'amount', category: 'betting', sortOrder: 15 },
    { key: 'time', category: 'common', sortOrder: 1 },
  ],
}

const DEFAULT_NOTIFICATION_TEMPLATE_CONTENT: Record<string, string> = {
  ODDS_PREMATCH_PUSH: `<b>赛前赔率变动</b>

联赛：{{league_name}}
比赛：{{match_title}}

{{market_lines}}

筛选：{{filter_summary}}
时间：<code>{{time}}</code>`,
  ODDS_LIVE_PUSH: `<b>滚球赔率变动</b>

联赛：{{league_name}}
比赛：{{match_title}}
进行：{{elapsed_minutes}}
比分：{{score_text}}

{{market_lines}}

筛选：{{filter_summary}}
时间：<code>{{time}}</code>`,
  BETTING_TEMPLATE: `<b>投注模板</b>

联赛：{{league_name}}
比赛：{{match_title}}
盘口：{{market_title}}
选择：{{selection_name}}
赔率：<code>{{odds}}</code>
金额：<code>{{amount}}</code>

时间：<code>{{time}}</code>`,
}
