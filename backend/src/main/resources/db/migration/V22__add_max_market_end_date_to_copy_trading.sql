-- ============================================
-- V22: 添加市场截止时间筛选功能
-- 1. 为 copy_trading 表添加 max_market_end_date 字段
-- 2. 为 markets 表添加 end_date 字段并清空已有数据
-- ============================================

-- 1. 添加市场截止时间筛选字段到 copy_trading 表
-- 仅跟单截止时间小于设置时间的订单
-- 存储毫秒时间戳，NULL 表示不启用此筛选
ALTER TABLE copy_trading
ADD COLUMN max_market_end_date BIGINT NULL COMMENT '市场截止时间限制（毫秒时间戳），仅跟单截止时间小于此时间的订单，NULL表示不启用';

-- 2. 为 markets 表添加 end_date 字段
-- 删除已有数据，让系统重新从 API 获取，确保所有数据的 end_date 都正确填充
DELETE FROM markets;

-- 添加市场截止时间字段
ALTER TABLE markets
ADD COLUMN end_date BIGINT NULL COMMENT '市场截止时间（毫秒时间戳），NULL表示未设置' AFTER archived;

