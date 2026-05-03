import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function readSource(relativePath: string): string {
  return fs.readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8')
}

describe('leader monitoring source rules', () => {
  it('leader list should keep balance lookup available even without copy-trading relations', () => {
    const leaderList = readSource('../src/pages/LeaderList.tsx')
    const types = readSource('../src/types/index.ts')

    expect(types).toContain('monitoringEnabled: boolean')
    expect(leaderList).toContain('apiService.leaders.balance({ leaderId: leader.id })')
    expect(leaderList).not.toContain("t('leaderList.notMonitoring')")
    expect(leaderList).not.toContain("t('leaderList.notMonitoringHint')")
    expect(leaderList).not.toContain("t('leaderDetail.notMonitoringTitle')")
    expect(leaderList).not.toContain("t('leaderDetail.notMonitoringDesc')")
  })

  it('leader list should load balances in parallel and show total, available, and position balances together', () => {
    const leaderList = readSource('../src/pages/LeaderList.tsx')

    expect(leaderList).toContain('Promise.allSettled')
    expect(leaderList).not.toContain('[leaders, balanceLoading, balanceMap, t]')
    expect(leaderList).toContain("title: t('leaderList.assetOverview')")
    expect(leaderList).toContain('formatUSDC(balance.total)')
    expect(leaderList).toContain('formatUSDC(balance.available)')
    expect(leaderList).toContain('formatUSDC(balance.position)')
    expect(leaderList).toContain("{t('leaderDetail.totalBalance')}")
    expect(leaderList).toContain("{t('leaderDetail.availableBalance')}")
    expect(leaderList).toContain("{t('leaderDetail.positionBalance')}")
    expect(leaderList).toContain("t('leaderList.currentPositions')")
    expect(leaderList).not.toContain("t('leaderList.currentPositionAmount')")
    expect(leaderList).toContain('positionsCount')
  })

  it('leader list should not let StrictMode cleanup freeze asset loading in a permanent spinner state', () => {
    const leaderList = readSource('../src/pages/LeaderList.tsx')

    expect(leaderList).not.toContain('const [balanceLoading, setBalanceLoading]')
    expect(leaderList).not.toContain('balanceLoadingRef')
    expect(leaderList).not.toContain('let cancelled = false')
    expect(leaderList).not.toContain('if (cancelled)')
    expect(leaderList).toContain('(leader) => !balanceMapRef.current[leader.id]')
  })
})
