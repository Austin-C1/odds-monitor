let authExpiredHandler: (() => void) | null = null

export const registerAuthExpiredHandler = (handler: () => void): void => {
  authExpiredHandler = handler
}

export const notifyAuthExpired = (): void => {
  authExpiredHandler?.()
}
