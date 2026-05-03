ALTER TABLE copy_trading
ADD COLUMN risk_tiers JSON NULL COMMENT '手动分档风控配置';

ALTER TABLE copy_order_tracking
ADD COLUMN matched_tier INT NULL COMMENT '命中的分档序号' AFTER leader_buy_quantity,
ADD COLUMN effective_ratio DECIMAL(20, 8) NULL COMMENT '实际生效的跟单倍率' AFTER matched_tier;
