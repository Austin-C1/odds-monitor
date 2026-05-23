import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import { i18nResources } from './resources'

/**
 * 检测系统语言
 * 支持的语言：zh-CN, zh-TW, en
 * 如果不支持，默认使用 zh-CN
 */
const detectSystemLanguage = (): string => {
  const systemLanguage = navigator.language || navigator.languages?.[0] || 'zh-CN'
  const lang = systemLanguage.toLowerCase()
  
  if (lang.startsWith('zh')) {
    if (lang.includes('tw') || lang.includes('hk') || lang.includes('mo')) {
      return 'zh-TW'
    }
    return 'zh-CN'
  }
  return 'zh-CN'
}

const detectLanguage = (): string => {
  // 从 localStorage 读取用户设置的语言
  const savedLanguage = localStorage.getItem('i18n_language')
  
  // 如果是 auto 或未设置，使用系统语言
  if (!savedLanguage || savedLanguage === 'auto') {
    return detectSystemLanguage()
  }
  
  // 如果设置了具体中文，使用用户设置；历史 en 设置统一回到中文界面
  if (['zh-CN', 'zh-TW'].includes(savedLanguage)) {
    return savedLanguage
  }
  
  // 默认使用系统语言
  return detectSystemLanguage()
}

i18n
  .use(initReactI18next)
  .init({
    resources: {
      ...i18nResources
    },
    lng: detectLanguage(),
    fallbackLng: 'zh-CN',
    interpolation: {
      escapeValue: false // React 已经转义了
    }
  })

export default i18n

/**
 * 切换语言
 */
export const changeLanguage = (lng: 'zh-CN' | 'zh-TW' | 'en') => {
  const nextLanguage = lng === 'en' ? 'zh-CN' : lng
  localStorage.setItem('i18n_language', nextLanguage)
  i18n.changeLanguage(nextLanguage)
}

/**
 * 获取当前语言
 */
export const getCurrentLanguage = (): string => {
  return i18n.language || 'zh-CN'
}
