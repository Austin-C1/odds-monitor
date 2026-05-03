-- ============================================
-- V4: 重构跟单系统
-- 1. 删除现有 copy_trading 记录
-- 2. 移除 template_id 字段，添加所有配置参数字段和过滤条件字段
-- 3. 在 copy_trading_templates 表中添加过滤条件字段
-- 4. 修改 copy_order_tracking 表，移除 template_id 字段
-- ============================================

-- 1. 删除现有 copy_trading 记录（根据需求直接删除）
DELETE FROM copy_trading;

-- 2. 删除外键约束（先查询外键名称，如果存在则删除）
SET @fk_name = (SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE 
                WHERE TABLE_SCHEMA = DATABASE() 
                AND TABLE_NAME = 'copy_trading' 
                AND REFERENCED_TABLE_NAME = 'copy_trading_templates' 
                LIMIT 1);
SET @sql = IF(@fk_name IS NOT NULL, 
              CONCAT('ALTER TABLE copy_trading DROP FOREIGN KEY ', @fk_name), 
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 删除 template_id 相关的索引（先查询索引是否存在）
SET @idx_name = (SELECT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS 
                 WHERE TABLE_SCHEMA = DATABASE() 
                 AND TABLE_NAME = 'copy_trading' 
                 AND INDEX_NAME = 'idx_template_id' 
                 LIMIT 1);
SET @sql = IF(@idx_name IS NOT NULL, 
              CONCAT('DROP INDEX ', @idx_name, ' ON copy_trading'), 
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 删除唯一约束 uk_account_template_leader
SET @uk_name = (SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS 
                WHERE TABLE_SCHEMA = DATABASE() 
                AND TABLE_NAME = 'copy_trading' 
                AND CONSTRAINT_TYPE = 'UNIQUE' 
                AND CONSTRAINT_NAME = 'uk_account_template_leader' 
                LIMIT 1);
SET @sql = IF(@uk_name IS NOT NULL, 
              CONCAT('ALTER TABLE copy_trading DROP INDEX ', @uk_name), 
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 删除 template_id 字段（如果存在）
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
                   WHERE TABLE_SCHEMA = DATABASE() 
                   AND TABLE_NAME = 'copy_trading' 
                   AND COLUMN_NAME = 'template_id');
SET @sql = IF(@col_exists > 0, 
              'ALTER TABLE copy_trading DROP COLUMN template_id', 
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. 添加所有配置参数字段到 copy_trading 表
ALTER TABLE copy_trading 
    ADD COLUMN copy_mode VARCHAR(10) NOT NULL DEFAULT 'RATIO' COMMENT '跟单金额模式（RATIO/FIXED）' AFTER leader_id,
    ADD COLUMN copy_ratio DECIMAL(10, 2) NOT NULL DEFAULT 1.00 COMMENT '跟单比例（仅在copyMode=RATIO时生效）' AFTER copy_mode,
    ADD COLUMN fixed_amount DECIMAL(20, 8) NULL COMMENT '固定跟单金额（仅在copyMode=FIXED时生效）' AFTER copy_ratio,
    ADD COLUMN max_order_size DECIMAL(20, 8) NOT NULL DEFAULT 1000.00000000 COMMENT '单笔订单最大金额（USDC）' AFTER fixed_amount,
    ADD COLUMN min_order_size DECIMAL(20, 8) NOT NULL DEFAULT 1.00000000 COMMENT '单笔订单最小金额（USDC）' AFTER max_order_size,
    ADD COLUMN max_daily_loss DECIMAL(20, 8) NOT NULL DEFAULT 10000.00000000 COMMENT '每日最大亏损限制（USDC）' AFTER min_order_size,
    ADD COLUMN max_daily_orders INT NOT NULL DEFAULT 100 COMMENT '每日最大跟单订单数' AFTER max_daily_loss,
    ADD COLUMN price_tolerance DECIMAL(5, 2) NOT NULL DEFAULT 5.00 COMMENT '价格容忍度（百分比，0-100）' AFTER max_daily_orders,
    ADD COLUMN delay_seconds INT NOT NULL DEFAULT 0 COMMENT '跟单延迟（秒，默认0立即跟单）' AFTER price_tolerance,
    ADD COLUMN poll_interval_seconds INT NOT NULL DEFAULT 5 COMMENT '轮询间隔（秒，仅在WebSocket不可用时使用）' AFTER delay_seconds,
    ADD COLUMN use_websocket BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否优先使用WebSocket推送' AFTER poll_interval_seconds,
    ADD COLUMN websocket_reconnect_interval INT NOT NULL DEFAULT 5000 COMMENT 'WebSocket重连间隔（毫秒）' AFTER use_websocket,
    ADD COLUMN websocket_max_retries INT NOT NULL DEFAULT 10 COMMENT 'WebSocket最大重试次数' AFTER websocket_reconnect_interval,
    ADD COLUMN support_sell BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否支持跟单卖出' AFTER websocket_max_retries,
    -- 过滤条件字段
    ADD COLUMN min_order_depth DECIMAL(20, 8) NULL COMMENT '最小订单深度（USDC金额），NULL表示不启用此过滤' AFTER support_sell,
    ADD COLUMN max_spread DECIMAL(20, 8) NULL COMMENT '最大价差（绝对价格），NULL表示不启用此过滤' AFTER min_order_depth,
    ADD COLUMN min_orderbook_depth DECIMAL(20, 8) NULL COMMENT '最小订单簿深度（USDC金额），NULL表示不启用此过滤' AFTER max_spread;

-- 6. 添加新的唯一约束（account_id + leader_id，不再包含 template_id）
ALTER TABLE copy_trading 
    ADD UNIQUE KEY uk_account_leader (account_id, leader_id);

-- 7. 在 copy_trading_templates 表中添加过滤条件字段
ALTER TABLE copy_trading_templates
    ADD COLUMN min_order_depth DECIMAL(20, 8) NULL COMMENT '最小订单深度（USDC金额），NULL表示不启用此过滤' AFTER support_sell,
    ADD COLUMN max_spread DECIMAL(20, 8) NULL COMMENT '最大价差（绝对价格），NULL表示不启用此过滤' AFTER min_order_depth,
    ADD COLUMN min_orderbook_depth DECIMAL(20, 8) NULL COMMENT '最小订单簿深度（USDC金额），NULL表示不启用此过滤' AFTER max_spread;

-- 8. 修改 copy_order_tracking 表，移除 template_id 字段（如果存在）
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
                   WHERE TABLE_SCHEMA = DATABASE() 
                   AND TABLE_NAME = 'copy_order_tracking' 
                   AND COLUMN_NAME = 'template_id');
SET @sql = IF(@col_exists > 0, 
              'ALTER TABLE copy_order_tracking DROP COLUMN template_id', 
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 9. 更新表注释
ALTER TABLE copy_trading COMMENT='跟单配置表（独立配置，不再绑定模板）';

