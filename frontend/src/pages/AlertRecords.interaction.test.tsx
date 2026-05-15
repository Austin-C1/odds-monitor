// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { message } from 'antd'
import { apiClient } from '../services/api'
import AlertRecords from './AlertRecords'

vi.mock('../services/api', () => ({
  apiClient: {
    post: vi.fn(),
  },
}))

vi.mock('antd', async () => {
  const actual = await vi.importActual<typeof import('antd')>('antd')
  return {
    ...actual,
    message: {
      error: vi.fn(),
    },
  }
})

describe('AlertRecords page', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: vi.fn().mockImplementation((query) => ({
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
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('loads alert records when backend returns a valid list', async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce({
      data: {
        code: 0,
        data: [
          {
            id: 1,
            alertType: 'odds_change',
            severity: 'info',
            matchName: '华伦西亚 vs 巴列卡诺',
            sourceKey: 'crown',
            title: '赔率变动',
            message: '皇冠：0.82 -> 0.92',
            createdAt: 1778780450325,
            acknowledged: false,
          },
        ],
      },
    })

    render(<AlertRecords />)

    expect(await screen.findByText('赔率变动')).toBeTruthy()
    expect(screen.getByText('华伦西亚 vs 巴列卡诺')).toBeTruthy()
    expect(screen.getByText('皇冠：0.82 -> 0.92')).toBeTruthy()
  })

  it('shows an error when backend returns an invalid response', async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce({
      data: { code: 500, data: null, msg: '读取失败' },
    })

    render(<AlertRecords />)

    await waitFor(() => {
      expect(message.error).toHaveBeenCalledWith('读取失败')
    })
  })

  it('shows an error when request fails', async () => {
    vi.mocked(apiClient.post).mockRejectedValueOnce(new Error('network failed'))

    render(<AlertRecords />)

    await waitFor(() => {
      expect(message.error).toHaveBeenCalledWith('network failed')
    })
  })
})
