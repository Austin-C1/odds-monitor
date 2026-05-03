-- ============================================
-- V5: 添加被过滤订单表
-- 用于记录因筛选条件不满足而被过滤的订单信息
-- ============================================

CREATE TABLE IF NOT EXISTS filtered_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    copy_trading_id BIGINT NOT NULL COMMENT '跟单关系ID',
    account_id BIGINT NOT NULL COMMENT '账户ID',
    leader_id BIGINT NOT NULL COMMENT 'Leader ID',
    leader_trade_id VARCHAR(100) NOT NULL COMMENT 'Leader 的交易ID',
    market_id VARCHAR(100) NOT NULL COMMENT '市场地址',
    market_title VARCHAR(500) NULL COMMENT '市场标题（从 API 获取）',
    market_slug VARCHAR(200) NULL COMMENT '市场 slug（用于生成链接）',
    side VARCHAR(10) NOT NULL COMMENT '订单方向：BUY 或 SELL',
    outcome_index INT NULL COMMENT '结果索引（0, 1, 2, ...），支持多元市场',
    outcome VARCHAR(50) NULL COMMENT '市场方向（如 YES, NO 等）',
    price DECIMAL(20, 8) NOT NULL COMMENT 'Leader 交易价格',
    size DECIMAL(20, 8) NOT NULL COMMENT 'Leader 交易数量',
    calculated_quantity DECIMAL(20, 8) NULL COMMENT '计算出的跟单数量（如果已计算）',
    filter_reason TEXT NOT NULL COMMENT '过滤原因（详细说明）',
    filter_type VARCHAR(50) NOT NULL COMMENT '过滤类型（如 ORDER_DEPTH, SPREAD, ORDERBOOK_DEPTH 等）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    INDEX idx_copy_trading (copy_trading_id),
    INDEX idx_leader_trade (leader_id, leader_trade_id),
    INDEX idx_market (market_id),
    INDEX idx_created_at (created_at),
    INDEX idx_filter_type (filter_type),
    FOREIGN KEY (copy_trading_id) REFERENCES copy_trading(id) ON DELETE CASCADE,
    FOREIGN KEY (leader_id) REFERENCES copy_trading_leaders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='被过滤订单表';

