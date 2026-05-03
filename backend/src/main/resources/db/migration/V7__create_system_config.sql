-- ============================================
-- V7: 创建系统配置表
-- 用于存储系统级别的配置（如 Builder API Key）
-- ============================================

CREATE TABLE IF NOT EXISTS system_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键（唯一）',
    config_value TEXT NULL COMMENT '配置值（加密存储）',
    description VARCHAR(255) NULL COMMENT '配置描述',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    INDEX idx_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';

-- 初始化 Builder API Key 配置项（如果不存在）
INSERT IGNORE INTO system_config (config_key, config_value, description, created_at, updated_at)
VALUES 
    ('builder.api_key', NULL, 'Builder API Key（用于 Gasless 交易）', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('builder.secret', NULL, 'Builder Secret（用于 Gasless 交易）', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('builder.passphrase', NULL, 'Builder Passphrase（用于 Gasless 交易）', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000);

