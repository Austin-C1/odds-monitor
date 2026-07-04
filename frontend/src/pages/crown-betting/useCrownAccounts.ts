import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { message } from 'antd'
import { apiClient } from '../../services/api'
import { extractApiErrorMessage } from '../../utils/apiError'
import {
  hasAdsPowerProfile,
  isBettingEnabledAccount,
  persistStoredAccounts,
  readStoredAccounts,
} from '../crownBettingAccounts'
import type {
  AdsPowerCrownSessionResponse,
  AdsPowerProfileStatus,
  AdsPowerStartProfileResponse,
  AdsPowerStatusResponse,
  ApiResponse,
  CrownAccount,
  CrownAccountFormValues,
  CrownAccountStatus,
} from '../crownBettingTypes'

export const useCrownAccounts = () => {
  const [accounts, setAccounts] = useState<CrownAccount[]>(readStoredAccounts)
  const [modalOpen, setModalOpen] = useState(false)
  const [adsPowerStatus, setAdsPowerStatus] = useState<AdsPowerStatusResponse | null>(null)
  const [adsPowerChecking, setAdsPowerChecking] = useState(false)
  const accountsRef = useRef<CrownAccount[]>(accounts)

  const saveAccounts = useCallback((nextAccounts: CrownAccount[]) => {
    setAccounts(nextAccounts)
    persistStoredAccounts(nextAccounts)
  }, [])

  const totalBalance = useMemo(
    () => accounts.reduce((total, account) => total + (account.balance || 0), 0),
    [accounts],
  )
  const abnormalCount = accounts.filter((account) => account.status === 'error').length
  const boundProfileCount = accounts.filter(hasAdsPowerProfile).length
  const enabledBettingAccounts = useMemo(
    () => accounts.filter(isBettingEnabledAccount),
    [accounts],
  )

  useEffect(() => {
    accountsRef.current = accounts
  }, [accounts])

  const updateAccount = useCallback((id: string, patch: Partial<CrownAccount>) => {
    setAccounts((currentAccounts) => {
      const nextAccounts = currentAccounts.map((account) => (
        account.id === id ? { ...account, ...patch } : account
      ))
      persistStoredAccounts(nextAccounts)
      return nextAccounts
    })
  }, [])

  const matchAdsPowerCrownSession = useCallback(async (account: CrownAccount) => {
    const preferredProfileId = account.adsPowerProfileId?.trim()
    return apiClient.post<ApiResponse<AdsPowerCrownSessionResponse>>(
      '/auto-betting/adspower/crown-session/match',
      { loginName: account.loginName, loginUrl: account.loginUrl, preferredProfileId: preferredProfileId || undefined },
    )
  }, [])

  const checkAccount = useCallback(async (account: CrownAccount) => {
    updateAccount(account.id, {
      status: 'checking',
      note: '正在检测 AdsPower 环境状态',
      lastCheckedAt: Date.now(),
    })
    try {
      const response = await matchAdsPowerCrownSession(account)
      if (response.data.code !== 0 || !response.data.data) {
        throw new Error(response.data.msg || '账号检测失败')
      }
      const result = response.data.data
      const checkedAt = result.checkedAt || Date.now()
      const isClosed = !result.opened && result.accountStatus === 'profile_closed'
      const nextAdsPowerStatus: AdsPowerProfileStatus = result.opened ? 'opened' : (isClosed ? 'closed' : 'error')
      const nextAccountStatus: CrownAccountStatus = result.loggedIn ? 'success' : (isClosed ? 'unchecked' : 'error')
      const note = result.loggedIn
        ? (typeof result.balance === 'number' ? '账号在线，余额已获取' : '账号在线，余额未读取到')
        : (isClosed ? 'AdsPower 环境未打开，请先打开环境并完成登录' : result.message || '账号检测失败')
      updateAccount(account.id, {
        status: nextAccountStatus,
        balance: typeof result.balance === 'number' ? result.balance : null,
        currency: result.currency || account.currency,
        adsPowerProfileId: result.loggedIn && result.profileId ? result.profileId : account.adsPowerProfileId,
        adsPowerStatus: nextAdsPowerStatus,
        adsPowerMessage: result.message,
        adsPowerUpdatedAt: checkedAt,
        lastCheckedAt: checkedAt,
        note,
      })
      if (result.loggedIn) {
        message.success('账号在线')
      } else if (isClosed) {
        message.warning('AdsPower 环境未打开')
      } else {
        message.error(result.message || '账号检测失败')
      }
    } catch (error: any) {
      const errorMessage = extractApiErrorMessage(error, '账号检测失败')
      updateAccount(account.id, {
        status: 'error',
        adsPowerStatus: 'error',
        balance: null,
        adsPowerMessage: errorMessage,
        adsPowerUpdatedAt: Date.now(),
        lastCheckedAt: Date.now(),
        note: errorMessage,
      })
      message.error(errorMessage)
    }
  }, [matchAdsPowerCrownSession, updateAccount])

  const checkAdsPowerStatus = useCallback(async () => {
    setAdsPowerChecking(true)
    try {
      const response = await apiClient.post<ApiResponse<AdsPowerStatusResponse>>(
        '/auto-betting/adspower/status',
        {},
      )
      if (response.data.code !== 0 || !response.data.data) {
        throw new Error(response.data.msg || 'AdsPower 检测失败')
      }
      const result = response.data.data
      setAdsPowerStatus(result)
      if (result.available) {
        message.success('AdsPower 已连接')
      } else {
        message.warning(`AdsPower 未连接：${result.message}`)
      }
    } catch (error: any) {
      const checkedAt = Date.now()
      const errorMessage = extractApiErrorMessage(error, 'AdsPower 检测失败')
      setAdsPowerStatus({
        available: false,
        baseUrl: '',
        message: errorMessage,
        checkedAt,
      })
      message.error(errorMessage)
    } finally {
      setAdsPowerChecking(false)
    }
  }, [])

  const openAdsPowerProfile = useCallback(async (account: CrownAccount) => {
    const profileId = account.adsPowerProfileId?.trim()
    if (!profileId) {
      message.warning('未绑定 AdsPower 档案')
      return
    }
    updateAccount(account.id, {
      adsPowerStatus: 'starting',
      adsPowerMessage: '正在打开 AdsPower 环境',
      adsPowerUpdatedAt: Date.now(),
    })
    try {
      const response = await apiClient.post<ApiResponse<AdsPowerStartProfileResponse>>(
        '/auto-betting/adspower/start-profile',
        { profileId },
      )
      if (response.data.code !== 0 || !response.data.data) {
        throw new Error(response.data.msg || 'AdsPower 环境打开失败')
      }
      const result = response.data.data
      updateAccount(account.id, {
        adsPowerStatus: result.opened ? 'opened' : 'error',
        status: account.status === 'checking' ? 'unchecked' : account.status,
        adsPowerMessage: result.opened ? 'AdsPower 环境已打开，可人工登录或监控' : result.message,
        adsPowerUpdatedAt: result.openedAt || Date.now(),
        lastCheckedAt: result.openedAt || Date.now(),
      })
      if (result.opened) {
        message.success('AdsPower 环境已打开')
      } else {
        message.warning(`AdsPower 环境打开失败：${result.message}`)
      }
    } catch (error: any) {
      const errorMessage = extractApiErrorMessage(error, 'AdsPower 环境打开失败')
      updateAccount(account.id, {
        adsPowerStatus: 'error',
        adsPowerMessage: errorMessage,
        adsPowerUpdatedAt: Date.now(),
      })
      message.error(errorMessage)
    }
  }, [updateAccount])

  const addAccount = useCallback((values: CrownAccountFormValues) => {
    const now = Date.now()
    const nextAccount: CrownAccount = {
      id: `crown-${now}`,
      displayName: values.displayName.trim(),
      loginName: values.loginName.trim(),
      loginUrl: values.loginUrl.trim(),
      adsPowerProfileId: values.adsPowerProfileId?.trim() || '',
      adsPowerStatus: values.adsPowerProfileId?.trim() ? 'unlinked' : undefined,
      bettingEnabled: false,
      status: 'unchecked',
      balance: null,
      currency: 'CNY',
      lastCheckedAt: now,
      note: values.note?.trim(),
    }
    saveAccounts([nextAccount, ...accountsRef.current])
    setModalOpen(false)
    message.success('皇冠账号已添加')
    void checkAccount(nextAccount)
  }, [checkAccount, saveAccounts])

  const deleteAccount = useCallback((id: string) => {
    saveAccounts(accountsRef.current.filter((account) => account.id !== id))
    message.success('皇冠账号已删除')
  }, [saveAccounts])

  const refreshAccounts = useCallback(async () => {
    for (const account of accountsRef.current) {
      await checkAccount(account)
      await new Promise((resolve) => setTimeout(resolve, 1200))
    }
    message.success('已完成账号状态检测')
  }, [checkAccount])

  return {
    accounts,
    accountsRef,
    modalOpen,
    setModalOpen,
    adsPowerStatus,
    adsPowerChecking,
    totalBalance,
    abnormalCount,
    boundProfileCount,
    enabledBettingAccounts,
    updateAccount,
    checkAccount,
    checkAdsPowerStatus,
    openAdsPowerProfile,
    addAccount,
    deleteAccount,
    refreshAccounts,
  }
}
