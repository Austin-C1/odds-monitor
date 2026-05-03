import { useState, useEffect } from 'react'
import { Card, Select, Space, Typography, message } from 'antd'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'

const { Title } = Typography

const LanguageSettings: React.FC = () => {
  const { t, i18n: i18nInstance } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  
  // 检测系统语言
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

  // 初始化当前语言设置
  const getInitialLanguage = (): string => {
    const savedLanguage = localStorage.getItem('i18n_language')
    return savedLanguage || 'auto'
  }

  const [currentLang, setCurrentLang] = useState<string>(getInitialLanguage())

  const languages = [
    { value: 'auto', label: t('languageSettings.followSystem') || '跟随系统' },
    { value: 'zh-CN', label: '简体中文' },
    { value: 'zh-TW', label: '繁體中文' },
    { value: 'en', label: 'English' }
  ]

  // 获取当前显示的语言（如果是 auto，显示系统语言）
  const getDisplayLanguage = (): string => {
    if (currentLang === 'auto') {
      return detectSystemLanguage()
    }
    return currentLang
  }

  const handleChange = async (value: string) => {
    try {
      let actualLang = value
      if (value === 'auto') {
        actualLang = detectSystemLanguage()
        // 保存 auto 到 localStorage，但使用系统语言
        localStorage.setItem('i18n_language', 'auto')
      } else {
        localStorage.setItem('i18n_language', value)
      }
      
      setCurrentLang(value)
      await i18nInstance.changeLanguage(actualLang)
      message.success(t('languageSettings.changeSuccess') || '语言切换成功')
      // 不需要刷新页面，i18n 和 Ant Design 的 locale 会自动更新
    } catch (error) {
      message.error(t('languageSettings.changeFailed') || '语言切换失败')
    }
  }

  // 初始化时，如果当前设置是 auto，确保使用系统语言
  useEffect(() => {
    const savedLanguage = localStorage.getItem('i18n_language')
    if (!savedLanguage || savedLanguage === 'auto') {
      const systemLang = detectSystemLanguage()
      if (i18nInstance.language !== systemLang) {
        i18nInstance.changeLanguage(systemLang)
      }
    } else {
      // 如果保存的是具体语言，确保使用该语言
      if (i18nInstance.language !== savedLanguage) {
        i18nInstance.changeLanguage(savedLanguage)
      }
    }
  }, [])

  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('languageSettings.title') || '语言设置'}</Title>
      </div>
      
      <Card>
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <div>
            <Typography.Text strong style={{ display: 'block', marginBottom: '8px' }}>
              {t('languageSettings.currentLanguage') || '当前语言'}
            </Typography.Text>
            <Select
              value={currentLang}
              onChange={handleChange}
              options={languages}
              style={{ width: isMobile ? '100%' : 200 }}
              size={isMobile ? 'middle' : 'large'}
            />
            {currentLang === 'auto' && (
              <div style={{ marginTop: '8px' }}>
                <Typography.Text type="secondary" style={{ fontSize: '12px' }}>
                  {t('languageSettings.currentSystemLanguage') || '当前系统语言'}: {
                    getDisplayLanguage() === 'zh-CN' ? '简体中文' :
                    getDisplayLanguage() === 'zh-TW' ? '繁體中文' : 'English'
                  }
                </Typography.Text>
              </div>
            )}
          </div>
          <div>
            <Typography.Text type="secondary">
              {t('languageSettings.description') || '切换语言后，界面将立即更新为新语言。'}
            </Typography.Text>
          </div>
        </Space>
      </Card>
    </div>
  )
}

export default LanguageSettings

