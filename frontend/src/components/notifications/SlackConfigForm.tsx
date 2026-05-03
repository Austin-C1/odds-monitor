import { Form, Input, Alert } from 'antd'
import { useTranslation } from 'react-i18next'

interface SlackConfigFormProps {
  form: any
}

/**
 * Slack 配置表单组件
 */
const SlackConfigForm: React.FC<SlackConfigFormProps> = ({ form: _form }) => {
  const { t } = useTranslation()
  
  return (
    <>
      <Alert
        message={t('slackConfig.title')}
        description={
          <div style={{ fontSize: '13px', lineHeight: '1.8' }}>
            <p style={{ margin: '4px 0' }}>{t('slackConfig.step1')}</p>
            <p style={{ margin: '4px 0' }}>{t('slackConfig.step2')}</p>
            <p style={{ margin: '4px 0' }}>{t('slackConfig.step3')}</p>
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
          rules={[{ required: true, message: t('slackConfig.webhookUrlRequired') }]}
        >
          <Input
            placeholder={t('slackConfig.webhookUrlPlaceholder')}
            addonBefore={t('slackConfig.webhookUrl')}
          />
        </Form.Item>
      </Form.Item>
    </>
  )
}

export default SlackConfigForm

