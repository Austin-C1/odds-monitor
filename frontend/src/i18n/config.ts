import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import zhCN from '../locales/zh-CN/common.json'
import zhTW from '../locales/zh-TW/common.json'
import en from '../locales/en/common.json'

/**
 * 检测系统语言
 * 支持的语言：zh-CN, zh-TW, en
 * 如果不支持，默认使用 en
 */
const detectSystemLanguage = (): string => {
  const systemLanguage = navigator.language || navigator.languages?.[0] || 'en'
  const lang = systemLanguage.toLowerCase()
  
  if (lang.startsWith('zh')) {
    if (lang.includes('tw') || lang.includes('hk') || lang.includes('mo')) {
      return 'zh-TW'
    }
    return 'zh-CN'
  }
  return 'en'
}

const detectLanguage = (): string => {
  // 从 localStorage 读取用户设置的语言
  const savedLanguage = localStorage.getItem('i18n_language')
  
  // 如果是 auto 或未设置，使用系统语言
  if (!savedLanguage || savedLanguage === 'auto') {
    return detectSystemLanguage()
  }
  
  // 如果设置了具体语言，使用设置的语言
  if (['zh-CN', 'zh-TW', 'en'].includes(savedLanguage)) {
    return savedLanguage
  }
  
  // 默认使用系统语言
  return detectSystemLanguage()
}

i18n
  .use(initReactI18next)
  .init({
    resources: {
      'zh-CN': {
        translation: zhCN
      },
      'zh-TW': {
        translation: zhTW
      },
      'en': {
        translation: en
      }
    },
    lng: detectLanguage(),
    fallbackLng: 'en',
    interpolation: {
      escapeValue: false // React 已经转义了
    }
  })

export default i18n

/**
 * 切换语言
 */
export const changeLanguage = (lng: 'zh-CN' | 'zh-TW' | 'en') => {
  localStorage.setItem('i18n_language', lng)
  i18n.changeLanguage(lng)
}

/**
 * 获取当前语言
 */
export const getCurrentLanguage = (): string => {
  return i18n.language || 'en'
}
