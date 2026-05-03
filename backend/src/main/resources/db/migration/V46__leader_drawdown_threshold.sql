ALTER TABLE leader_copy_trading_control
    ADD COLUMN drawdown_threshold_percent DECIMAL(10, 2) NOT NULL DEFAULT 25.00 AFTER current_drawdown_percent;

CREATE INDEX idx_copy_order_tracking_copy_trading_created_at
    ON copy_order_tracking(copy_trading_id, created_at);

CREATE INDEX idx_copy_order_tracking_notification_sent
    ON copy_order_tracking(notification_sent, account_id);

CREATE INDEX idx_sell_match_record_copy_trading_created_at
    ON sell_match_record(copy_trading_id, created_at);

CREATE INDEX idx_sell_match_record_price_updated
    ON sell_match_record(price_updated, copy_trading_id);
