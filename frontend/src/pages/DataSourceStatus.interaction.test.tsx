// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render } from '@testing-library/react'
import { apiClient } from '../services/api'
import DataSourceStatus from './DataSourceStatus'

vi.mock('../services/api', () => ({
  apiClient: {
    post: vi.fn(),
  },
}))

describe('DataSourceStatus page', () => {
  beforeEach(() => {
    vi.useFakeTimers()
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
    vi.mocked(apiClient.post).mockResolvedValue({
      data: {
        code: 0,
        data: [],
        msg: 'ok',
      },
    })
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('refreshes collector status while the page stays open', async () => {
    const { unmount } = render(<DataSourceStatus />)

    await act(async () => {
      await Promise.resolve()
    })
    expect(apiClient.post).toHaveBeenCalledTimes(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })

    expect(apiClient.post).toHaveBeenCalledTimes(2)

    unmount()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })

    expect(apiClient.post).toHaveBeenCalledTimes(2)
  })
})
