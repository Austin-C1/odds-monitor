import { Form, Input, Alert } from 'antd'
import { useTranslation } from 'react-i18next'

interface DiscordConfigFormProps {
  form: any
}

/**
 * Discord 配置表单组件
 */
const DiscordConfigForm: React.FC<DiscordConfigFormProps> = ({ form: _form }) => {
  const { t } = useTranslation()
  
  return (
    <>
      <Alert
        message={t('discordConfig.title')}
        description={
          <div style={{ fontSize: '13px', lineHeight: '1.8' }}>
            <p style={{ margin: '4px 0' }}>{t('discordConfig.step1')}</p>
            <p style={{ margin: '4px 0' }}>{t('discordConfig.step2')}</p>
            <p style={{ margin: '4px 0' }}>{t('discordConfig.step3')}</p>
          </div>
        }
        type="info"
        showIcon
        style={{ fontSize: '12px', marginBottom: 16 }}
      />
      
      <Form.Item
        label={t('notificationSettings.chatIds')}
        required
      >
        <Form.Item
          name={['config', 'webhookUrl']}
          rules={[{ required: true, message: t('discordConfig.webhookUrlRequired') }]}
        >
          <Input
            placeholder={t('discordConfig.webhookUrlPlaceholder')}
            addonBefore={t('discordConfig.webhookUrl')}
          />
        </Form.Item>
      </Form.Item>
    </>
  )
}

export default DiscordConfigForm

