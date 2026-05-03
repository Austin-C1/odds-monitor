import { describe, expect, it } from 'vitest'
import type { NotificationConfig } from '../types'
import { isTelegramConfigReadyForTest } from './notificationSettingsHelpers'

const buildConfig = (overrides: Partial<NotificationConfig> = {}): NotificationConfig => ({
  id: 1,
  type: 'telegram',
  name: 'tg-1',
  enabled: true,
  config: {
    data: {
      botToken: 'bot-token',
      chatIds: ['10001'],
      monitorModeEnabled: false,
    },
  },
  ...overrides,
})

describe('isTelegramConfigReadyForTest', () => {
  it('returns false when bot token is missing', () => {
    expect(
      isTelegramConfigReadyForTest(
        buildConfig({
          config: {
            data: {
              botToken: '   ',
              chatIds: ['10001'],
              monitorModeEnabled: false,
            },
          },
        })
      )
    ).toBe(false)
  })

  it('returns true when bot token and chat ids are both present', () => {
    expect(isTelegramConfigReadyForTest(buildConfig())).toBe(true)
  })
})
