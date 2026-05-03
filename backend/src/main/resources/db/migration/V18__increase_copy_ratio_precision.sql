-- ============================================
-- V18: 增加 copy_ratio 字段的精度，支持更小的小数值
-- ============================================

-- 修改 copy_trading 表的 copy_ratio 字段精度
-- 从 DECIMAL(10, 2) 改为 DECIMAL(20, 8)，支持更小的跟单比例值（如 0.001）
ALTER TABLE copy_trading 
MODIFY COLUMN copy_ratio DECIMAL(20, 8) NOT NULL DEFAULT 1.00000000 COMMENT '跟单比例（仅在copyMode=RATIO时生效）';

-- 修改 copy_trading_templates 表的 copy_ratio 字段精度
-- 从 DECIMAL(10, 2) 改为 DECIMAL(20, 8)，支持更小的跟单比例值（如 0.001）
ALTER TABLE copy_trading_templates 
MODIFY COLUMN copy_ratio DECIMAL(20, 8) NOT NULL DEFAULT 1.00000000 COMMENT '跟单比例（仅在copyMode=RATIO时生效）';

