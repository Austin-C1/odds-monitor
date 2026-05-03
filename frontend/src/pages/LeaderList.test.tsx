/* @vitest-environment jsdom */

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LeaderList from './LeaderList'

const navigateMock = vi.fn()
const leadersListMock = vi.fn()
const leaderBalanceMock = vi.fn()
const leaderDetailMock = vi.fn()
const copyTradingListMock = vi.fn()
const statisticsBatchDetailMock = vi.fn()

const translations: Record<string, string> = {
  'common.actions': 'Actions',
  'common.cancel': 'Cancel',
  'common.close': 'Close',
  'common.confirm': 'Confirm',
  'common.edit': 'Edit',
  'common.viewDetail': 'View detail',
  'leaderDetail.availableBalance': 'Cash',
  'leaderDetail.balanceInfo': 'Balance info',
  'leaderDetail.copyTradingCount': 'Copy trading count',
  'leaderDetail.fetchBalanceFailed': 'Failed to fetch balance',
  'leaderDetail.leaderAddress': 'Leader address',
  'leaderDetail.leaderName': 'Leader name',
  'leaderDetail.market': 'Market',
  'leaderDetail.noPositions': 'No positions',
  'leaderDetail.openWebsite': 'Open website',
  'leaderDetail.pnl': 'PnL',
  'leaderDetail.positionBalance': 'Position value',
  'leaderDetail.positionCount': 'Positions',
  'leaderDetail.positions': 'Positions',
  'leaderDetail.quantity': 'Quantity',
  'leaderDetail.refresh': 'Refresh',
  'leaderDetail.remark': 'Remark',
  'leaderDetail.side': 'Side',
  'leaderDetail.title': 'Leader detail',
  'leaderDetail.totalBalance': 'Total balance',
  'leaderDetail.updatedAt': 'Updated at',
  'leaderDetail.totalPnl': 'Total pnl',
  'leaderList.addLeader': 'Add leader',
  'leaderList.assetOverview': 'Asset overview',
  'leaderList.collapseGroup': 'Collapse group',
  'leaderList.currentPositions': 'Positions',
  'leaderList.customGroup': 'Custom group',
  'leaderList.deleteConfirm': 'Delete leader?',
  'leaderList.expandGroup': 'Expand group',
  'leaderList.fetchFailed': 'Failed to fetch leaders',
  'leaderList.leaderName': 'Leader name',
  'leaderList.noData': 'No leaders',
  'leaderList.openWebsite': 'Open website',
  'leaderList.remark': 'Remark',
  'leaderList.title': 'Leader list',
  'leaderList.totalPnl': 'Total pnl',
  'leaderList.ungrouped': 'Ungrouped',
  'leaderList.viewBacktests': 'View backtests',
  'leaderList.viewCopyTradings': 'View copy tradings',
}

vi.mock('react-responsive', () => ({
  useMediaQuery: () => false,
}))

vi.mock('react-router-dom', () => ({
  useNavigate: () => navigateMock,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: { defaultValue?: string }) => translations[key] ?? options?.defaultValue ?? key,
    i18n: { language: 'en' },
  }),
}))

vi.mock('../services/api', () => ({
  apiService: {
    leaders: {
      list: (...args: unknown[]) => leadersListMock(...args),
      balance: (...args: unknown[]) => leaderBalanceMock(...args),
      detail: (...args: unknown[]) => leaderDetailMock(...args),
      delete: vi.fn(),
    },
    copyTrading: {
      list: (...args: unknown[]) => copyTradingListMock(...args),
    },
    statistics: {
      batchDetail: (...args: unknown[]) => statisticsBatchDetailMock(...args),
    },
  },
}))

function createLeader(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    leaderAddress: '0x1111111111111111111111111111111111111111',
    leaderName: 'Alpha',
    category: 'sports',
    customGroup: 'Focus',
    remark: undefined,
    website: undefined,
    copyTradingCount: 0,
    monitoringEnabled: true,
    backtestCount: 0,
    createdAt: 0,
    updatedAt: 1713669600000,
    ...overrides,
  }
}

function getGroupCard(groupName: string) {
  const groupHeading = screen.getAllByText(groupName)[0]
  const card = groupHeading.closest('.ant-card')
  expect(card).toBeTruthy()
  return card as HTMLElement
}

function expandGroup(groupName: string) {
  const card = getGroupCard(groupName)
  fireEvent.click(within(card).getByRole('button', { name: 'Expand group' }))
}

describe('LeaderList', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: vi.fn().mockImplementation((query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      })),
    })

    Object.defineProperty(window, 'getComputedStyle', {
      writable: true,
      value: vi.fn().mockImplementation(() => ({
        getPropertyValue: () => '',
        overflow: 'visible',
        overflowX: 'visible',
        overflowY: 'visible',
      })),
    })

    navigateMock.mockReset()
    leadersListMock.mockReset()
    leaderBalanceMock.mockReset()
    leaderDetailMock.mockReset()
    copyTradingListMock.mockReset()
    statisticsBatchDetailMock.mockReset()

    leadersListMock.mockResolvedValue({
      data: {
        code: 0,
        data: {
          list: [
            createLeader({
              id: 1,
              leaderName: 'DrPufferfish',
              customGroup: 'Focus',
              remark: 'Sports',
            }),
            createLeader({
              id: 2,
              leaderAddress: '0x2222222222222222222222222222222222222222',
              leaderName: 'debased',
              customGroup: null,
            }),
            createLeader({
              id: 3,
              leaderAddress: '0x3333333333333333333333333333333333333333',
              leaderName: 'islav900',
              customGroup: 'Focus',
            }),
          ],
        },
      },
    })

    leaderBalanceMock.mockImplementation(({ leaderId }: { leaderId: number }) => Promise.resolve({
      data: {
        code: 0,
        data: {
          leaderId,
          leaderAddress: `0x${leaderId.toString().padStart(40, '0')}`,
          leaderName: leaderId === 1 ? 'DrPufferfish' : `Leader ${leaderId}`,
          totalBalance: '100',
          availableBalance: '60',
          positionBalance: '40',
          positions: [
            {
              marketId: 'm1',
              title: 'Will the Celtics win?',
              side: 'YES',
              quantity: '12',
              avgPrice: '0.45',
              currentValue: '20',
              pnl: '4',
            },
            {
              marketId: 'm2',
              title: 'Will BTC hit 100k?',
              side: 'NO',
              quantity: '8',
              avgPrice: '0.32',
              currentValue: '20',
              pnl: '-2',
            },
          ],
        },
      },
    }))

    leaderDetailMock.mockImplementation(({ leaderId }: { leaderId: number }) => Promise.resolve({
      data: {
        code: 0,
        data: createLeader({
          id: leaderId,
          leaderName: leaderId === 1 ? 'DrPufferfish' : 'Leader',
          customGroup: leaderId === 1 ? 'Focus' : null,
          remark: 'Sports',
        }),
      },
    }))

    copyTradingListMock.mockResolvedValue({
      data: {
        code: 0,
        data: {
          list: [
            { id: 101, leaderId: 1, accountId: 9001 },
            { id: 102, leaderId: 1, accountId: 9002 },
            { id: 103, leaderId: 2, accountId: 9001 },
          ],
          total: 3,
        },
      },
    })

    statisticsBatchDetailMock.mockResolvedValue({
      data: {
        code: 0,
        data: {
          list: [
            { copyTradingId: 101, totalPnl: '30' },
            { copyTradingId: 102, totalPnl: '-5' },
            { copyTradingId: 103, totalPnl: '8' },
          ],
        },
      },
    })
  })

  afterEach(() => {
    cleanup()
  })

  it('shows groups collapsed by default and expands them on demand', async () => {
    render(<LeaderList />)

    await waitFor(() => {
      expect(leadersListMock).toHaveBeenCalled()
    })

    expect(screen.getAllByText('Focus').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Ungrouped').length).toBeGreaterThan(0)
    expect(screen.queryByText('DrPufferfish')).toBeNull()
    expect(screen.queryByText('islav900')).toBeNull()
    expect(screen.queryByText('debased')).toBeNull()

    expandGroup('Focus')

    await waitFor(() => {
      expect(screen.getByText('DrPufferfish')).toBeTruthy()
      expect(screen.getByText('islav900')).toBeTruthy()
    })
    expect(screen.queryByText('debased')).toBeNull()
  })

  it('shows position, cash, position value and total pnl in asset overview after expanding a group', async () => {
    render(<LeaderList />)

    await waitFor(() => {
      expect(screen.getAllByText('Focus').length).toBeGreaterThan(0)
    })

    expandGroup('Focus')

    await waitFor(() => {
      expect(copyTradingListMock).toHaveBeenCalled()
      expect(statisticsBatchDetailMock).toHaveBeenCalled()
    })

    expect(screen.getAllByText('Positions').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Cash').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Position value').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Total pnl').length).toBeGreaterThan(0)
    expect(screen.queryByText('Weekly pnl')).toBeNull()
    expect(screen.getByText('+25 USDC')).toBeTruthy()
  })

  it('shows summary metrics and positions inside leader detail modal after expanding a group', async () => {
    render(<LeaderList />)

    await waitFor(() => {
      expect(screen.getAllByText('Focus').length).toBeGreaterThan(0)
    })

    expandGroup('Focus')

    const row = await screen.findByText('DrPufferfish')
    const tableRow = row.closest('tr')
    expect(tableRow).toBeTruthy()

    const buttons = within(tableRow as HTMLElement).getAllByRole('button')
    fireEvent.click(buttons[0])

    await waitFor(() => {
      expect(leaderDetailMock).toHaveBeenCalledWith({ leaderId: 1 })
    })

    expect(screen.getAllByText('Positions').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Cash').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Position value').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Total pnl').length).toBeGreaterThan(0)
    expect(screen.queryByText('Weekly pnl')).toBeNull()
    expect(screen.getByText('Will the Celtics win?')).toBeTruthy()
    expect(screen.getByText('Will BTC hit 100k?')).toBeTruthy()
  })
})
