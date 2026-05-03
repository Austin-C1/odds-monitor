-- ============================================
-- 创建消息推送配置表
-- 支持多种推送方式（Telegram、Discord、Slack 等）
-- ============================================

CREATE TABLE IF NOT EXISTS notification_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(50) NOT NULL COMMENT '推送类型（telegram、discord、slack 等）',
    name VARCHAR(100) NOT NULL COMMENT '配置名称（用于显示）',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
    config_json TEXT NOT NULL COMMENT '配置信息（JSON格式，不同类型存储不同字段）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    INDEX idx_type (type),
    INDEX idx_enabled (enabled),
    INDEX idx_type_enabled (type, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息推送配置表';

-- ============================================
-- 配置说明
-- ============================================
-- config_json 字段存储 JSON 格式的配置信息
-- 
-- Telegram 配置示例：
-- {
--   "botToken": "123456789:ABCdefGHIjklMNOpqrsTUVwxyz",
--   "chatIds": ["123456789", "987654321"]
-- }
--
-- Discord 配置示例（未来扩展）：
-- {
--   "webhookUrl": "https://discord.com/api/webhooks/..."
-- }
--
-- Slack 配置示例（未来扩展）：
-- {
--   "webhookUrl": "https://hooks.slack.com/services/..."
-- }

