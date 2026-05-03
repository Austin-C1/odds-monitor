-- ============================================
-- V10: 添加 Leader 备注和网站字段
-- ============================================

ALTER TABLE copy_trading_leaders 
ADD COLUMN remark TEXT NULL COMMENT 'Leader 备注（可选）',
ADD COLUMN website VARCHAR(500) NULL COMMENT 'Leader 网站（可选）';

