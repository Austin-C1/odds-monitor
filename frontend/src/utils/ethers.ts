import { ethers } from 'ethers'

/**
 * 从私钥推导钱包地址
 */
export function getAddressFromPrivateKey(privateKey: string): string {
  try {
    // 移除 0x 前缀（如果有）
    const cleanKey = privateKey.startsWith('0x') ? privateKey.slice(2) : privateKey
    
    // 创建钱包
    const wallet = new ethers.Wallet(`0x${cleanKey}`)
    return wallet.address
  } catch (error) {
    throw new Error(`无效的私钥: ${error}`)
  }
}

/**
 * 从助记词推导钱包地址
 * @param mnemonic 助记词（12或24个单词，用空格分隔）
 * @param index 派生路径索引（默认0，使用第一个地址）
 */
export function getAddressFromMnemonic(mnemonic: string, index: number = 0): string {
  try {
    // 验证助记词格式
    if (!isValidMnemonic(mnemonic)) {
      throw new Error('助记词格式不正确')
    }
    
    // ethers.js v6: 如果 index 为 0，可以直接使用 Wallet.fromPhrase
    // 它默认使用路径 m/44'/60'/0'/0/0
    if (index === 0) {
      const wallet = ethers.Wallet.fromPhrase(mnemonic.trim())
      return wallet.address
    }
    
    // 对于其他索引，使用 HDNodeWallet
    // 从助记词创建 Mnemonic 对象
    const mnemonicObj = ethers.Mnemonic.fromPhrase(mnemonic.trim())
    
    // 使用标准 BIP44 路径直接创建钱包：m/44'/60'/0'/0/index
    // ethers.js v6: HDNodeWallet.fromMnemonic 可以直接指定路径
    const derivationPath = `m/44'/60'/0'/0/${index}`
    const wallet = ethers.HDNodeWallet.fromMnemonic(mnemonicObj, derivationPath)
    
    return wallet.address
  } catch (error: any) {
    // 如果直接指定路径失败，尝试分步派生
    try {
      const mnemonicObj = ethers.Mnemonic.fromPhrase(mnemonic.trim())
      // 先创建根节点（不指定路径）
      const rootNode = ethers.HDNodeWallet.fromMnemonic(mnemonicObj)
      // 使用相对路径（不以 m/ 开头）
      const relativePath = `44'/60'/0'/0/${index}`
      const wallet = rootNode.derivePath(relativePath)
      return wallet.address
    } catch (fallbackError: any) {
      throw new Error(`无效的助记词: ${error.message || error}`)
    }
  }
}

/**
 * 从助记词导出私钥
 * @param mnemonic 助记词（12或24个单词，用空格分隔）
 * @param index 派生路径索引（默认0，使用第一个地址）
 */
export function getPrivateKeyFromMnemonic(mnemonic: string, index: number = 0): string {
  try {
    // 验证助记词格式
    if (!isValidMnemonic(mnemonic)) {
      throw new Error('助记词格式不正确')
    }
    
    // ethers.js v6: 如果 index 为 0，可以直接使用 Wallet.fromPhrase
    // 它默认使用路径 m/44'/60'/0'/0/0
    if (index === 0) {
      const wallet = ethers.Wallet.fromPhrase(mnemonic.trim())
      return wallet.privateKey
    }
    
    // 对于其他索引，使用 HDNodeWallet
    // 从助记词创建 Mnemonic 对象
    const mnemonicObj = ethers.Mnemonic.fromPhrase(mnemonic.trim())
    
    // 使用标准 BIP44 路径直接创建钱包：m/44'/60'/0'/0/index
    // ethers.js v6: HDNodeWallet.fromMnemonic 可以直接指定路径
    const derivationPath = `m/44'/60'/0'/0/${index}`
    const wallet = ethers.HDNodeWallet.fromMnemonic(mnemonicObj, derivationPath)
    
    return wallet.privateKey
  } catch (error: any) {
    // 如果直接指定路径失败，尝试分步派生
    try {
      const mnemonicObj = ethers.Mnemonic.fromPhrase(mnemonic.trim())
      // 先创建根节点（不指定路径）
      const rootNode = ethers.HDNodeWallet.fromMnemonic(mnemonicObj)
      // 使用相对路径（不以 m/ 开头）
      const relativePath = `44'/60'/0'/0/${index}`
      const wallet = rootNode.derivePath(relativePath)
      return wallet.privateKey
    } catch (fallbackError: any) {
      throw new Error(`无法从助记词导出私钥: ${error.message || error}`)
    }
  }
}

/**
 * 验证助记词格式
 */
export function isValidMnemonic(mnemonic: string): boolean {
  try {
    if (!mnemonic || !mnemonic.trim()) {
      return false
    }
    
    const words = mnemonic.trim().split(/\s+/)
    // 助记词应该是 12 或 24 个单词
    if (words.length !== 12 && words.length !== 24) {
      return false
    }
    
    // 使用 ethers 验证助记词
    ethers.Mnemonic.fromPhrase(mnemonic.trim())
    return true
  } catch {
    return false
  }
}

/**
 * 验证钱包地址格式
 */
export function isValidWalletAddress(address: string): boolean {
  return /^0x[a-fA-F0-9]{40}$/.test(address)
}

/**
 * 验证私钥格式
 */
export function isValidPrivateKey(privateKey: string): boolean {
  try {
    const cleanKey = privateKey.startsWith('0x') ? privateKey.slice(2) : privateKey
    return /^[a-fA-F0-9]{64}$/.test(cleanKey)
  } catch {
    return false
  }
}

