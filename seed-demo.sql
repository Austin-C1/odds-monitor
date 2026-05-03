SET NAMES utf8mb4;
UPDATE wallet_accounts SET is_enabled = 1 WHERE id IN (1,2);
INSERT INTO wallet_accounts (id, private_key, wallet_address, proxy_address, account_name, is_default, is_enabled, wallet_type, created_at, updated_at)
SELECT 3, 'demo-disabled-private-key-c', '0x1000000000000000000000000000000000000003', '0x2000000000000000000000000000000000000003', 'DemoAccount-3', 0, 1, 'magic', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
WHERE NOT EXISTS (SELECT 1 FROM wallet_accounts WHERE id = 3);
INSERT INTO copy_trading_leaders (id, leader_address, leader_name, category, created_at, updated_at, remark, website)
SELECT 3, '0x3000000000000000000000000000000000000003', 'DemoLeader-3', 'sports', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, 'demo data', 'https://example.com/demo-leader-3'
WHERE NOT EXISTS (SELECT 1 FROM copy_trading_leaders WHERE id = 3);
INSERT INTO copy_trading (id, account_id, leader_id, max_order_size, min_order_size, max_daily_loss, max_daily_orders, price_tolerance, delay_seconds, poll_interval_seconds, use_websocket, websocket_reconnect_interval, websocket_max_retries, support_sell, enabled, follow_settings_enabled, created_at, updated_at, keyword_filter_mode, config_name, push_failed_orders, max_market_end_date, push_filtered_orders)
SELECT 7, 2, 1, 200.00000000, 5.00000000, 500.00000000, 20, 5.00, 0, 5, 1, 5000, 10, 1, 1, 1, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, 'DISABLED', 'DemoCopy-L1-A2', 0, NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM copy_trading WHERE id = 7);
INSERT INTO copy_trading (id, account_id, leader_id, max_order_size, min_order_size, max_daily_loss, max_daily_orders, price_tolerance, delay_seconds, poll_interval_seconds, use_websocket, websocket_reconnect_interval, websocket_max_retries, support_sell, enabled, follow_settings_enabled, created_at, updated_at, keyword_filter_mode, config_name, push_failed_orders, max_market_end_date, push_filtered_orders)
SELECT 8, 1, 2, 180.00000000, 5.00000000, 400.00000000, 18, 6.00, 0, 5, 1, 5000, 10, 1, 1, 1, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, 'DISABLED', 'DemoCopy-L2-A1', 1, NULL, 0
WHERE NOT EXISTS (SELECT 1 FROM copy_trading WHERE id = 8);
INSERT INTO copy_trading (id, account_id, leader_id, max_order_size, min_order_size, max_daily_loss, max_daily_orders, price_tolerance, delay_seconds, poll_interval_seconds, use_websocket, websocket_reconnect_interval, websocket_max_retries, support_sell, enabled, follow_settings_enabled, created_at, updated_at, keyword_filter_mode, config_name, push_failed_orders, max_market_end_date, push_filtered_orders)
SELECT 9, 3, 3, 150.00000000, 3.00000000, 300.00000000, 12, 4.00, 0, 5, 1, 5000, 10, 0, 1, 1, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, 'DISABLED', 'DemoCopy-L3-A3', 0, NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM copy_trading WHERE id = 9);
INSERT INTO copy_trading_follow_rule (copy_trading_id, min_leader_amount, max_leader_amount, follow_amount, follow_max_amount, sort_order, created_at, updated_at)
SELECT 7, 0.00000000, 100.00000000, 12.00000000, 18.00000000, 1, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
WHERE NOT EXISTS (SELECT 1 FROM copy_trading_follow_rule WHERE copy_trading_id = 7 AND sort_order = 1);
INSERT INTO copy_trading_follow_rule (copy_trading_id, min_leader_amount, max_leader_amount, follow_amount, follow_max_amount, sort_order, created_at, updated_at)
SELECT 7, 100.00000000, 500.00000000, 28.00000000, 45.00000000, 2, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
WHERE NOT EXISTS (SELECT 1 FROM copy_trading_follow_rule WHERE copy_trading_id = 7 AND sort_order = 2);
INSERT INTO copy_trading_follow_rule (copy_trading_id, min_leader_amount, max_leader_amount, follow_amount, follow_max_amount, sort_order, created_at, updated_at)
SELECT 8, 0.00000000, 200.00000000, 20.00000000, 35.00000000, 1, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
WHERE NOT EXISTS (SELECT 1 FROM copy_trading_follow_rule WHERE copy_trading_id = 8 AND sort_order = 1);
INSERT INTO copy_trading_follow_rule (copy_trading_id, min_leader_amount, max_leader_amount, follow_amount, follow_max_amount, sort_order, created_at, updated_at)
SELECT 8, 200.00000000, NULL, 40.00000000, 80.00000000, 2, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
WHERE NOT EXISTS (SELECT 1 FROM copy_trading_follow_rule WHERE copy_trading_id = 8 AND sort_order = 2);
INSERT INTO copy_trading_follow_rule (copy_trading_id, min_leader_amount, max_leader_amount, follow_amount, follow_max_amount, sort_order, created_at, updated_at)
SELECT 9, 0.00000000, 120.00000000, 8.00000000, 12.00000000, 1, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
WHERE NOT EXISTS (SELECT 1 FROM copy_trading_follow_rule WHERE copy_trading_id = 9 AND sort_order = 1);
INSERT INTO copy_trading_follow_rule (copy_trading_id, min_leader_amount, max_leader_amount, follow_amount, follow_max_amount, sort_order, created_at, updated_at)
SELECT 9, 120.00000000, NULL, 18.00000000, 30.00000000, 2, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
WHERE NOT EXISTS (SELECT 1 FROM copy_trading_follow_rule WHERE copy_trading_id = 9 AND sort_order = 2);
SELECT id, account_name, wallet_address, is_enabled FROM wallet_accounts ORDER BY id;
SELECT id, leader_name, leader_address FROM copy_trading_leaders ORDER BY id;
SELECT id, config_name, account_id, leader_id, enabled, follow_settings_enabled, support_sell, push_failed_orders, push_filtered_orders FROM copy_trading ORDER BY id;
SELECT copy_trading_id, min_leader_amount, max_leader_amount, follow_amount, follow_max_amount, sort_order FROM copy_trading_follow_rule ORDER BY copy_trading_id, sort_order;
