import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  DEFAULT_NOTIFICATION_TEMPLATE_TYPE,
  getVisibleNotificationTemplateTypes,
} from './notificationTemplateOptions'
import { i18nResources } from '../i18n/resources'

describe('NotificationSettingsPage template configuration', () => {
  it('defaults to prematch odds template', () => {
    const source = fs.readFileSync(path.resolve(__dirname, 'NotificationSettingsPage.tsx'), 'utf8')

    expect(DEFAULT_NOTIFICATION_TEMPLATE_TYPE).toBe('ODDS_PREMATCH_PUSH')
    expect(source).toContain('useState<string>(DEFAULT_NOTIFICATION_TEMPLATE_TYPE)')
    expect(source).not.toContain("useState<string>('ORDER_SUCCESS')")
  })

  it('only keeps focused template labels in locale configuration', () => {
    const templateTypes = Object.keys(
      i18nResources['zh-CN'].translation.notificationSettings.templateTypes
    )

    expect(templateTypes).toEqual(['ODDS_PREMATCH_PUSH', 'ODDS_LIVE_PUSH', 'BETTING_TEMPLATE'])
  })

  it('filters stale backend template types to the focused template set', () => {
    const visibleTypes = getVisibleNotificationTemplateTypes([
      { type: 'ORDER_SUCCESS', name: 'Order Success', description: 'old' },
      { type: 'MONITOR_SAME_SIDE', name: 'Same Side', description: 'old' },
    ])

    expect(visibleTypes.map((type) => type.type)).toEqual([
      'ODDS_PREMATCH_PUSH',
      'ODDS_LIVE_PUSH',
      'BETTING_TEMPLATE',
    ])
  })
})
