CREATE TABLE IF NOT EXISTS large_bet_monitor_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    football_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    basketball_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    single_trade_threshold DECIMAL(20, 8) NOT NULL DEFAULT 5000.00000000,
    cumulative_trade_threshold DECIMAL(20, 8) NOT NULL DEFAULT 15000.00000000,
    rolling_window_minutes INT NOT NULL DEFAULT 60,
    check_interval_seconds INT NOT NULL DEFAULT 30,
    telegram_config_id BIGINT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='large bet monitor config';

CREATE TABLE IF NOT EXISTS large_bet_watch_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trader_address VARCHAR(42) NOT NULL,
    trader_name VARCHAR(255) NULL,
    profile_url VARCHAR(500) NOT NULL,
    market_id VARCHAR(100) NOT NULL,
    market_slug VARCHAR(255) NULL,
    market_title VARCHAR(500) NOT NULL,
    sport_type VARCHAR(30) NOT NULL,
    outcome VARCHAR(100) NOT NULL,
    trigger_reason VARCHAR(30) NOT NULL,
    last_single_amount DECIMAL(20, 8) NOT NULL DEFAULT 0.00000000,
    last_cumulative_amount DECIMAL(20, 8) NOT NULL DEFAULT 0.00000000,
    first_triggered_at BIGINT NOT NULL,
    last_triggered_at BIGINT NOT NULL,
    trigger_count INT NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_large_bet_record (trader_address, market_id, outcome),
    INDEX idx_large_bet_last_triggered_at (last_triggered_at),
    INDEX idx_large_bet_sport_type (sport_type),
    INDEX idx_large_bet_trader_address (trader_address)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='large bet triggered watch records';
