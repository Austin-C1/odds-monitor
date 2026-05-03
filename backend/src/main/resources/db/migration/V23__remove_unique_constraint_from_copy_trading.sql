-- ============================================
-- V23: 移除跟单关系的唯一约束
-- 允许同一用户创建多个相同 leader 的跟单配置
-- ============================================

-- 删除唯一约束 uk_account_leader
SET @uk_name = (SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS 
                WHERE TABLE_SCHEMA = DATABASE() 
                AND TABLE_NAME = 'copy_trading' 
                AND CONSTRAINT_TYPE = 'UNIQUE' 
                AND CONSTRAINT_NAME = 'uk_account_leader' 
                LIMIT 1);
SET @sql = IF(@uk_name IS NOT NULL, 
              CONCAT('ALTER TABLE copy_trading DROP INDEX ', @uk_name), 
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

