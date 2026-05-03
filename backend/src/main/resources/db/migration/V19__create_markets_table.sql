-- ============================================
-- V19: 创建市场信息表
-- ============================================

-- 创建 markets 表
CREATE TABLE IF NOT EXISTS markets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    market_id VARCHAR(100) NOT NULL UNIQUE COMMENT '市场ID（condition ID）',
    title VARCHAR(500) NOT NULL COMMENT '市场名称（question）',
    slug VARCHAR(200) COMMENT '市场slug',
    category VARCHAR(50) COMMENT '市场分类',
    icon VARCHAR(500) COMMENT '市场图标URL',
    image VARCHAR(500) COMMENT '市场图片URL',
    description TEXT COMMENT '市场描述',
    active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否活跃',
    closed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已关闭',
    archived BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已归档',
    created_at BIGINT NOT NULL COMMENT '创建时间（时间戳，毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（时间戳，毫秒）',
    INDEX idx_market_id (market_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='市场信息表';

