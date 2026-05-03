ALTER TABLE copy_trading_templates
    ADD COLUMN risk_tiers TEXT NULL COMMENT '手动分档风控配置 JSON';
