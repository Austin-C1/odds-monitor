import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function readSource(relativePath: string): string {
  return fs.readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8')
}

describe('notification error handling source rules', () => {
  it('notification settings surfaces backend messages instead of raw axios status text', () => {
    const pageSource = readSource('../src/pages/NotificationSettingsPage.tsx')
    const formSource = readSource('../src/components/notifications/TelegramConfigForm.tsx')

    expect(pageSource).toContain('extractApiErrorMessage')
    expect(formSource).toContain('extractApiErrorMessage')

    expect(pageSource).not.toContain("message.error(error.message || t('notificationSettings.fetchFailed'))")
    expect(pageSource).not.toContain("message.error(error.message || t('notificationSettings.testFailed'))")
    expect(pageSource).not.toContain("message.error(error.message || t('message.error'))")
    expect(formSource).not.toContain("message.error(error.message || t('notificationSettings.getChatIdsFailed'))")
  })

  it('notification settings disables unavailable test actions until a usable bot config exists', () => {
    const pageSource = readSource('../src/pages/NotificationSettingsPage.tsx')

    expect(pageSource).toContain('const hasReadyTestConfig = readyTestConfigs.length > 0')
    expect(pageSource).toContain("disabled={!hasReadyTestConfig}")
    expect(pageSource).toContain("t('notificationSettings.testUnavailable')")
    expect(pageSource).toContain('const isConfigReadyForTest = useCallback((config: NotificationConfig) => isTelegramConfigReadyForTest(config), [])')
    expect(pageSource).toContain("from './notificationSettingsHelpers'")
    expect(pageSource).toContain('disabled={!isConfigReadyForTest(record)}')
  })

  it('notification settings uses the same readable test message for row test actions', () => {
    const pageSource = readSource('../src/pages/NotificationSettingsPage.tsx')

    expect(pageSource).toContain("const TEST_NOTIFICATION_MESSAGE = '这是一条测试消息'")
    expect(pageSource).toContain('message: TEST_NOTIFICATION_MESSAGE')
  })
})
