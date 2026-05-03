-- ============================================
-- 添加 token_version 字段到 users 表
-- 用于使修改密码后的所有JWT token失效
-- 注意：如果V1脚本已包含此字段，此迁移会被跳过（Flyway会处理）
-- ============================================

-- 检查字段是否存在，如果不存在则添加
-- MySQL不支持IF NOT EXISTS，但Flyway会处理重复执行的情况
-- 如果字段已存在，此语句会报错，但Flyway会记录迁移已执行，不会重复执行

-- 使用存储过程检查并添加字段（如果不存在）
DELIMITER $$

CREATE PROCEDURE IF NOT EXISTS add_token_version_if_not_exists()
BEGIN
    DECLARE column_exists INT DEFAULT 0;
    
    SELECT COUNT(*) INTO column_exists
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'users'
      AND COLUMN_NAME = 'token_version';
    
    IF column_exists = 0 THEN
        ALTER TABLE users 
        ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0 COMMENT 'Token版本号，修改密码时递增，用于使所有JWT失效';
    END IF;
END$$

DELIMITER ;

-- 执行存储过程
CALL add_token_version_if_not_exists();

-- 删除存储过程
DROP PROCEDURE IF EXISTS add_token_version_if_not_exists;

-- 为现有用户设置默认tokenVersion为0（如果值为NULL）
UPDATE users SET token_version = 0 WHERE token_version IS NULL;

