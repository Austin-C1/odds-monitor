type ApiErrorShape = {
  message?: string
  response?: {
    data?: {
      msg?: string
      message?: string
    } | string
  }
}

const RAW_STATUS_MESSAGE_PATTERN = /^Request failed with status code \d+$/i

export const extractApiErrorMessage = (error: unknown, fallback: string): string => {
  const apiError = error as ApiErrorShape | undefined
  const responseData = apiError?.response?.data

  if (responseData && typeof responseData === 'object') {
    if (typeof responseData.msg === 'string' && responseData.msg.trim()) {
      return responseData.msg.trim()
    }

    if (typeof responseData.message === 'string' && responseData.message.trim()) {
      return responseData.message.trim()
    }
  }

  if (typeof responseData === 'string' && responseData.trim()) {
    return responseData.trim()
  }

  const message = apiError?.message?.trim()
  if (message && !RAW_STATUS_MESSAGE_PATTERN.test(message)) {
    return message
  }

  return fallback
}
