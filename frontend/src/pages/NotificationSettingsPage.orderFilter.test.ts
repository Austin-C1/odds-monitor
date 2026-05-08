import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(__dirname, '..')

describe('NotificationSettingsPage alert filter settings', () => {
  it('shows combined water and odds move filters for odds monitor alerts without old order amount settings', () => {
    const source = fs.readFileSync(path.join(root, 'pages', 'NotificationSettingsPage.tsx'), 'utf8')

    expect(source).not.toContain('orderNotificationMinAmountUsdc')
    expect(source).not.toContain('updateOrderNotificationMinAmount')
    expect(source).toContain('InputNumber')
    expect(source).toContain('handicapCombinedWaterMin')
    expect(source).toContain('totalCombinedWaterMin')
    expect(source).toContain('max={2}')
    expect(source).toContain('waterLimitModalConfig')
    expect(source).toContain('openWaterLimitModal(record)')
    expect(source).toContain('title="水位限制"')
    expect(source).toContain('Polymarket')
    expect(source).toContain('不受合水限制')
    expect(source).toContain('让球合水')
    expect(source).toContain('大小球合水')
    expect(source).toContain('oddsMoveFilterModalConfig')
    expect(source).toContain('openOddsMoveFilterModal(record)')
    expect(source).toContain('title="动水筛选"')
    expect(source).toContain('handicapOddsMoveMin')
    expect(source).toContain('totalOddsMoveMin')
    expect(source).toContain('moneylineOddsMoveMin')
    expect(source).toContain('只推送水位变化达到阈值')
    expect(source).toContain('{formatWaterLimitSummary(record)}')
    expect(source).toContain('{formatOddsMoveFilterSummary(record)}')
    expect(source).toContain('prematchWindowMinutes')
    expect(source).toContain('prematchWindowModalConfig')
    expect(source).toContain('openPrematchWindowModal(record)')
    expect(source).toContain('赛前区间盯梢')
    expect(source).toContain('比赛开赛后由滚球模式处理')
    expect(source).toContain('{formatPrematchWindowSummary(record)}')
    expect(source).not.toContain('只看滚球')
    expect(source).not.toContain('只看赛前')
    expect(source).not.toContain('{getMonitorModeEnabled(record) && (')
    expect(source).not.toContain('fetchLeaders')
  })

  it('does not define an API endpoint to save the order notification minimum amount', () => {
    const source = fs.readFileSync(path.join(root, 'services', 'api.ts'), 'utf8')

    expect(source).not.toContain('updateOrderNotificationMinAmount')
    expect(source).not.toContain('/system/config/order-notification-min-amount/update')
  })

  it('caps combined water and odds move filters at 2 on the backend', () => {
    const source = fs.readFileSync(
      path.join(root, '..', '..', 'backend', 'src', 'main', 'kotlin', 'com', 'wrbug', 'polymarketbot', 'service', 'system', 'NotificationConfigService.kt'),
      'utf8'
    )

    expect(source).toContain('MAX_COMBINED_WATER')
    expect(source).toContain('java.math.BigDecimal("2")')
    expect(source).toContain('cannot exceed 2')
    expect(source).toContain('requireOptionalOddsMoveLimit')
  })
})
