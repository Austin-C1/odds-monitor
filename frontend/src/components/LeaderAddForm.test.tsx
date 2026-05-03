/* @vitest-environment jsdom */

import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Form, message } from 'antd'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LeaderAddForm from './LeaderAddForm'

const addLeaderMock = vi.fn()

const translations: Record<string, string> = {
  'common.cancel': 'Cancel',
  'leaderAdd.add': 'Add Leader',
  'leaderAdd.addFailed': 'Failed to add Leader',
  'leaderAdd.leaderAddress': 'Leader Wallet Address',
  'leaderAdd.leaderAddressInvalid': 'Invalid wallet address',
  'leaderAdd.leaderAddressRequired': 'Please enter Leader wallet address',
  'leaderAdd.leaderName': 'Leader Name',
  'leaderAdd.remark': 'Leader Remark',
  'leaderAdd.website': 'Leader Website',
}

vi.mock('react-responsive', () => ({
  useMediaQuery: () => false,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: { defaultValue?: string }) => translations[key] ?? options?.defaultValue ?? key,
  }),
}))

vi.mock('../services/api', () => ({
  apiService: {
    leaders: {
      add: (...args: unknown[]) => addLeaderMock(...args),
    },
  },
}))

const TestHarness: React.FC<{ onSuccess?: (leaderId: number) => void }> = ({ onSuccess }) => {
  const [form] = Form.useForm()
  return <LeaderAddForm form={form} onSuccess={onSuccess} />
}

describe('LeaderAddForm', () => {
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

    addLeaderMock.mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    cleanup()
  })

  it('shows the backend error message when adding a leader fails', async () => {
    addLeaderMock.mockResolvedValue({
      data: {
        code: 1001,
        data: null,
        msg: 'Leader address already exists',
      },
    })

    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => {
      return (() => undefined) as ReturnType<typeof message.error>
    })
    const onSuccess = vi.fn()
    const user = userEvent.setup()

    render(<TestHarness onSuccess={onSuccess} />)

    await user.type(
      screen.getByLabelText(/Leader Wallet Address/),
      '0xe542afd3881c4c330ba0ebbb603bb470b2ba0a37',
    )
    await user.click(screen.getByRole('button', { name: 'Add Leader' }))

    await waitFor(() => {
      expect(addLeaderMock).toHaveBeenCalledWith({
        leaderAddress: '0xe542afd3881c4c330ba0ebbb603bb470b2ba0a37',
        leaderName: undefined,
        customGroup: undefined,
        remark: undefined,
        website: undefined,
      })
    })

    await waitFor(() => {
      expect(errorSpy).toHaveBeenCalledWith('Leader address already exists')
    })
    expect(onSuccess).not.toHaveBeenCalled()
  })
})
