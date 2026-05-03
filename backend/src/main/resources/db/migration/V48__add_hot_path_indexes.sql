ALTER TABLE copy_trading
    ADD INDEX idx_copy_trading_leader_enabled (leader_id, enabled);

ALTER TABLE copy_order_tracking
    ADD INDEX idx_copy_order_tracking_sell_match (copy_trading_id, market_id, outcome_index, remaining_quantity);

ALTER TABLE copy_order_tracking
    ADD INDEX idx_copy_order_tracking_position_value (copy_trading_id, market_id, outcome_index, remaining_quantity, price);
