-- ============================================
-- V49: Builder 与自动赎回迁移到账号级配置
-- ============================================

ALTER TABLE wallet_accounts
    ADD COLUMN builder_api_key VARCHAR(500) NULL AFTER api_passphrase,
    ADD COLUMN builder_secret VARCHAR(500) NULL AFTER builder_api_key,
    ADD COLUMN builder_passphrase VARCHAR(500) NULL AFTER builder_secret,
    ADD COLUMN auto_redeem_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER is_enabled;

UPDATE wallet_accounts
SET builder_api_key = (
        SELECT sc.config_value
        FROM system_config sc
        WHERE sc.config_key = 'builder.api_key'
        LIMIT 1
    ),
    builder_secret = (
        SELECT sc.config_value
        FROM system_config sc
        WHERE sc.config_key = 'builder.secret'
        LIMIT 1
    ),
    builder_passphrase = (
        SELECT sc.config_value
        FROM system_config sc
        WHERE sc.config_key = 'builder.passphrase'
        LIMIT 1
    )
WHERE builder_api_key IS NULL
  AND builder_secret IS NULL
  AND builder_passphrase IS NULL;

UPDATE wallet_accounts
SET auto_redeem_enabled = CASE
    WHEN EXISTS (
        SELECT 1
        FROM system_config sc
        WHERE sc.config_key = 'auto_redeem'
          AND LOWER(COALESCE(sc.config_value, 'true')) = 'false'
    ) THEN 0
    ELSE 1
END;
