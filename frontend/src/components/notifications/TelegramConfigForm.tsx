import { useState, type ReactNode } from 'react'
import { Form, Input, Alert, Button, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../../services/api'
import { extractApiErrorMessage } from '../../utils/apiError'

interface TelegramConfigFormProps {
  form: any
}

const STRONG_TAG_PATTERN = /<strong>(.*?)<\/strong>/gi
const HTML_TAG_PATTERN = /<[^>]+>/g

const stripHtml = (value: string): string => value.replace(HTML_TAG_PATTERN, '')

const renderInstruction = (text: string): ReactNode[] => {
  const nodes: ReactNode[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null

  while ((match = STRONG_TAG_PATTERN.exec(text)) !== null) {
    const leadingText = text.slice(lastIndex, match.index)
    if (leadingText) {
      nodes.push(stripHtml(leadingText))
    }

    nodes.push(
      <strong key={`strong-${match.index}`}>
        {stripHtml(match[1] || '')}
      </strong>
    )

    lastIndex = match.index + match[0].length
  }

  const trailingText = text.slice(lastIndex)
  if (trailingText) {
    nodes.push(stripHtml(trailingText))
  }

  return nodes.length > 0 ? nodes : [stripHtml(text)]
}

/**
 * Telegram 配置表单组件
 */
const TelegramConfigForm: React.FC<TelegramConfigFormProps> = ({ form }) => {
  const { t } = useTranslation()
  const [loading, setLoading] = useState(false)
  
  /**
   * Automatically fetch available Chat IDs.
   */
  const handleGetChatIds = async () => {
    const botToken = form.getFieldValue(['config', 'botToken'])
    if (!botToken || botToken.trim() === '') {
      message.warning(t('notificationSettings.getChatIdsNoToken'))
      return
    }
    
    setLoading(true)
    try {
      const response = await apiService.notifications.getTelegramChatIds({ botToken: botToken.trim() })
      if (response.data.code === 0 && response.data.data) {
        const chatIds = response.data.data
        if (chatIds.length > 0) {
          // Merge newly fetched chat IDs with the existing list.
          const existingChatIds = form.getFieldValue(['config', 'chatIds']) || ''
          const existingArray = typeof existingChatIds === 'string' 
            ? existingChatIds.split(',').map((id: string) => id.trim()).filter((id: string) => id)
            : Array.isArray(existingChatIds) ? existingChatIds : []
          
          // Remove duplicates before writing back to the form.
          const allChatIds = [...new Set([...existingArray, ...chatIds])]
          form.setFieldsValue({
            config: {
              ...form.getFieldValue('config'),
              chatIds: allChatIds.join(',')
            }
          })
          message.success(t('notificationSettings.getChatIdsSuccess', { count: chatIds.length }))
        } else {
          message.warning(t('notificationSettings.getChatIdsNoMessage'))
        }
      } else {
        message.error(response.data.msg || t('notificationSettings.getChatIdsFailed'))
      }
    } catch (error: any) {
      message.error(extractApiErrorMessage(error, t('notificationSettings.getChatIdsFailed')))
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <>
      <Alert
        message={t('telegramConfig.title')}
        description={
          <div style={{ fontSize: '13px', lineHeight: '1.8' }}>
            <p style={{ margin: '4px 0' }}>1. {renderInstruction(t('telegramConfig.step1'))}</p>
            <p style={{ margin: '4px 0' }}>2. {renderInstruction(t('telegramConfig.step2'))}</p>
            <p style={{ margin: '4px 0' }}>3. {renderInstruction(t('telegramConfig.step3'))}</p>
            <p style={{ margin: '4px 0' }}>{stripHtml(t('telegramConfig.step4'))}</p>
            <p style={{ margin: '4px 0' }}>{stripHtml(t('telegramConfig.step5'))}</p>
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
          name={['config', 'botToken']}
          rules={[{ required: true, message: t('telegramConfig.botTokenRequired') }]}
          style={{ marginBottom: 16 }}
        >
          <Input.Password
            placeholder={t('telegramConfig.botTokenPlaceholder')}
            addonBefore={t('telegramConfig.botToken')}
            addonAfter={
              <Button
                type="link"
                size="small"
                icon={<ReloadOutlined />}
                loading={loading}
                onClick={handleGetChatIds}
                style={{ padding: 0, height: 'auto' }}
              >
                {t('notificationSettings.getChatIdsButton')}
              </Button>
            }
          />
        </Form.Item>
        
        <Form.Item
          name={['config', 'chatIds']}
          rules={[{ required: true, message: t('notificationSettings.chatIdsRequired') }]}
          extra={t('notificationSettings.chatIdsExtra')}
        >
          <Input.TextArea
            placeholder={t('notificationSettings.chatIdsPlaceholder')}
            rows={3}
          />
        </Form.Item>
      </Form.Item>

    </>
  )
}

export default TelegramConfigForm
