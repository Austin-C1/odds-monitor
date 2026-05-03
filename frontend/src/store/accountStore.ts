import { create } from 'zustand'
import type { Account, AccountBalanceResponse, AccountUpdateRequest } from '../types'
import { apiService } from '../services/api'

interface AccountStore {
  accounts: Account[]
  currentAccount: Account | null
  loading: boolean
  error: string | null

  fetchAccounts: () => Promise<void>
  setCurrentAccount: (account: Account | null) => void
  importAccount: (data: any) => Promise<void>
  updateAccount: (data: AccountUpdateRequest) => Promise<Account>
  deleteAccount: (accountId: number) => Promise<void>
  fetchAccountDetail: (accountId: number) => Promise<Account>
  fetchAccountBalance: (accountId: number) => Promise<AccountBalanceResponse>
}

export const useAccountStore = create<AccountStore>((set, get) => ({
  accounts: [],
  currentAccount: null,
  loading: false,
  error: null,

  fetchAccounts: async () => {
    set({ loading: true, error: null })
    try {
      const response = await apiService.accounts.list()
      if (response.data.code === 0 && response.data.data) {
        const accounts = response.data.data.list || []
        set({ accounts, loading: false })

        if (accounts.length > 0 && !get().currentAccount) {
          set({ currentAccount: accounts[0] })
        }
      } else {
        set({ error: response.data.msg || '获取账户列表失败', loading: false })
      }
    } catch (error: any) {
      set({ error: error.message || '获取账户列表失败', loading: false })
    }
  },

  setCurrentAccount: (account) => {
    set({ currentAccount: account })
  },

  importAccount: async (data) => {
    set({ loading: true, error: null })
    try {
      const response = await apiService.accounts.import(data)
      if (response.data.code === 0) {
        await get().fetchAccounts()
      } else {
        const err = new Error(response.data.msg || '导入账户失败')
        ;(err as Error & { code?: number }).code = response.data.code
        set({ error: response.data.msg || '导入账户失败', loading: false })
        throw err
      }
    } catch (error: any) {
      set({ error: error.message || '导入账户失败', loading: false })
      throw error
    }
  },

  updateAccount: async (data) => {
    set({ loading: true, error: null })
    try {
      const response = await apiService.accounts.update(data)
      if (response.data.code === 0 && response.data.data) {
        const updatedAccount = response.data.data
        await get().fetchAccounts()
        set((state) => ({
          currentAccount: state.currentAccount?.id === updatedAccount.id ? updatedAccount : state.currentAccount
        }))
        return updatedAccount
      }
      set({ error: response.data.msg || '更新账户失败', loading: false })
      throw new Error(response.data.msg || '更新账户失败')
    } catch (error: any) {
      set({ error: error.message || '更新账户失败', loading: false })
      throw error
    }
  },

  deleteAccount: async (accountId) => {
    set({ loading: true, error: null })
    try {
      const response = await apiService.accounts.delete({ accountId })
      if (response.data.code === 0) {
        await get().fetchAccounts()
      } else {
        set({ error: response.data.msg || '删除账户失败', loading: false })
        throw new Error(response.data.msg || '删除账户失败')
      }
    } catch (error: any) {
      set({ error: error.message || '删除账户失败', loading: false })
      throw error
    }
  },

  fetchAccountDetail: async (accountId) => {
    set({ loading: true, error: null })
    try {
      const response = await apiService.accounts.detail({ accountId })
      if (response.data.code === 0 && response.data.data) {
        set({ loading: false })
        return response.data.data
      }
      const errorMsg = response.data.msg || '获取账户详情失败'
      set({ error: errorMsg, loading: false })
      throw new Error(errorMsg)
    } catch (error: any) {
      set({ error: error.message || '获取账户详情失败', loading: false })
      throw error
    }
  },

  fetchAccountBalance: async (accountId) => {
    const response = await apiService.accounts.balance({ accountId })
    if (response.data.code === 0 && response.data.data) {
      return response.data.data
    }
    throw new Error(response.data.msg || '获取账户余额失败')
  }
}))
