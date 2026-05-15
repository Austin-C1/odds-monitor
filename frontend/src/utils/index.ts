export const formatNumber = (value: string | number | undefined | null, maxDecimals?: number): string => {
  if (value === undefined || value === null || value === '') {
    return ''
  }

  const num = typeof value === 'string' ? parseFloat(value) : value
  if (Number.isNaN(num)) {
    return ''
  }

  let raw = maxDecimals === undefined
    ? num.toString()
    : (Math.floor(num * 10 ** maxDecimals) / 10 ** maxDecimals).toFixed(maxDecimals)
  if (raw.includes('.')) {
    raw = raw.replace(/\.?0+$/, '')
  }
  const [integerPart, decimalPart] = raw.split('.')
  const formattedInteger = integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')

  return decimalPart ? `${formattedInteger}.${decimalPart}` : formattedInteger
}

export const formatUSDC = (value: string | number | undefined | null): string => {
  const formatted = formatNumber(value, 4)
  return formatted || '-'
}

export const copyToClipboard = async (text: string): Promise<boolean> => {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }

    const textArea = document.createElement('textarea')
    textArea.value = text
    textArea.style.position = 'fixed'
    textArea.style.left = '-999999px'
    textArea.style.top = '-999999px'
    document.body.appendChild(textArea)
    textArea.focus()
    textArea.select()
    const successful = document.execCommand('copy')
    document.body.removeChild(textArea)
    return successful
  } catch {
    return false
  }
}

export {
  getToken,
  setToken,
  removeToken,
  hasToken
} from './auth'

export {
  getVersionInfo,
  getVersionText,
  getGitHubTagUrl
} from './version'
