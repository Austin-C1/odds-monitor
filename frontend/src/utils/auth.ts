/**
 * Token 管理工具
 */

const TOKEN_KEY = 'jwt_token'

function decodeJwtPayload(token: string): { exp?: number } | null {
  const segments = token.split('.')
  if (segments.length < 2) {
    return null
  }

  try {
    const normalized = segments[1]
      .replace(/-/g, '+')
      .replace(/_/g, '/')
      .padEnd(Math.ceil(segments[1].length / 4) * 4, '=')
    return JSON.parse(globalThis.atob(normalized)) as { exp?: number }
  } catch {
    return null
  }
}

function isExpiredToken(token: string): boolean {
  const payload = decodeJwtPayload(token)
  return typeof payload?.exp === 'number' && payload.exp * 1000 <= Date.now()
}

/**
 * 获取 token
 */
export const getToken = (): string | null => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (!token) {
    return null
  }

  if (isExpiredToken(token)) {
    localStorage.removeItem(TOKEN_KEY)
    return null
  }

  return token
}

/**
 * 保存 token
 */
export const setToken = (token: string): void => {
  localStorage.setItem(TOKEN_KEY, token)
}

/**
 * 清除 token
 */
export const removeToken = (): void => {
  localStorage.removeItem(TOKEN_KEY)
}

/**
 * 检查是否有 token
 */
export const hasToken = (): boolean => {
  return getToken() !== null
}

