import { describe, expect, it } from 'vitest'

import { extractApiErrorMessage } from '../src/utils/apiError'

describe('extractApiErrorMessage', () => {
  it('prefers backend message from a 400 response', () => {
    const error = {
      message: 'Request failed with status code 400',
      response: {
        data: {
          code: 4613,
          msg: '获取 Chat IDs 失败：未找到 Chat ID，请先向机器人发送一条消息（如 /start），然后重试'
        }
      }
    }

    expect(extractApiErrorMessage(error, '默认失败提示')).toBe(
      '获取 Chat IDs 失败：未找到 Chat ID，请先向机器人发送一条消息（如 /start），然后重试'
    )
  })

  it('falls back when backend message is missing', () => {
    const error = {
      message: 'Network Error'
    }

    expect(extractApiErrorMessage(error, '默认失败提示')).toBe('Network Error')
  })
})
