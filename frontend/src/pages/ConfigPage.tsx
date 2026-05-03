import { Card, Typography, Alert } from 'antd'
import { InfoCircleOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'

const { Title } = Typography

/**
 * 全局配置页面
 * 注意：全局配置功能已迁移到模板和跟单关系管理
 * 请使用"跟单模板"和"跟单配置"页面进行配置
 */
const ConfigPage: React.FC = () => {
  const { t } = useTranslation()
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('configPage.title') || '全局配置'}</Title>
      </div>
      
      <Card>
        <Alert
          message={t('configPage.message') || '配置功能已迁移'}
          description={
            <div>
              <p>{t('configPage.description') || '全局配置功能已迁移到以下页面：'}</p>
              <ul>
                <li><strong>{t('configPage.templates') || '跟单模板'}</strong>：{t('configPage.templatesDesc') || '管理跟单参数（比例、金额、风险控制等）'}</li>
                <li><strong>{t('configPage.copyTrading') || '跟单配置'}</strong>：{t('configPage.copyTradingDesc') || '将账户、模板和 Leader 关联，启用跟单关系'}</li>
                <li><strong>{t('configPage.systemSettings') || '系统管理'}</strong>：{t('configPage.systemSettingsDesc') || '配置代理、查看 API 健康状态'}</li>
              </ul>
              <p>{t('configPage.footer') || '请使用上述页面进行配置管理。'}</p>
            </div>
          }
          type="info"
          icon={<InfoCircleOutlined />}
          showIcon
        />
      </Card>
    </div>
  )
}

export default ConfigPage
