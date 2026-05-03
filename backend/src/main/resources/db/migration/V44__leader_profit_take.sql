ALTER TABLE leader_copy_trading_control
    ADD COLUMN profit_take_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER auto_pause_enabled,
    ADD COLUMN profit_take_price DECIMAL(10, 4) NOT NULL DEFAULT 0.9900 AFTER profit_take_enabled;
