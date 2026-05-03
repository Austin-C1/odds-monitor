CREATE TABLE IF NOT EXISTS odds_matches (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sport VARCHAR(32) NOT NULL DEFAULT 'football',
    league_name VARCHAR(128) NOT NULL,
    home_team VARCHAR(128) NOT NULL,
    away_team VARCHAR(128) NOT NULL,
    start_time BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'scheduled',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    INDEX idx_odds_matches_start_time (start_time),
    INDEX idx_odds_matches_status (status)
);

CREATE TABLE IF NOT EXISTS odds_platform_matches (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_key VARCHAR(32) NOT NULL,
    source_match_id VARCHAR(128) NOT NULL,
    raw_league_name VARCHAR(128) NOT NULL,
    raw_home_team VARCHAR(128) NOT NULL,
    raw_away_team VARCHAR(128) NOT NULL,
    raw_start_time BIGINT NULL,
    raw_payload_json TEXT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_odds_platform_match (source_key, source_match_id),
    INDEX idx_odds_platform_matches_source (source_key)
);

CREATE TABLE IF NOT EXISTS odds_match_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    match_id BIGINT NOT NULL,
    platform_match_id BIGINT NOT NULL,
    confidence DECIMAL(8, 4) NOT NULL DEFAULT 0,
    match_method VARCHAR(32) NOT NULL DEFAULT 'manual',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_odds_match_link (match_id, platform_match_id),
    INDEX idx_odds_match_links_match (match_id),
    INDEX idx_odds_match_links_platform (platform_match_id)
);

CREATE TABLE IF NOT EXISTS odds_markets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    match_id BIGINT NOT NULL,
    source_key VARCHAR(32) NOT NULL,
    market_type VARCHAR(32) NOT NULL,
    line_value VARCHAR(32) NULL,
    selection_name VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    INDEX idx_odds_markets_match_source (match_id, source_key),
    INDEX idx_odds_markets_type (market_type)
);

CREATE TABLE IF NOT EXISTS odds_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_id BIGINT NOT NULL,
    source_key VARCHAR(32) NOT NULL,
    odds_value DECIMAL(18, 8) NOT NULL,
    implied_probability DECIMAL(18, 8) NULL,
    captured_at BIGINT NOT NULL,
    raw_payload_json TEXT NULL,
    INDEX idx_odds_snapshots_market_time (market_id, captured_at),
    INDEX idx_odds_snapshots_source_time (source_key, captured_at)
);

CREATE TABLE IF NOT EXISTS odds_alert_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    alert_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'info',
    match_id BIGINT NULL,
    source_key VARCHAR(32) NULL,
    title VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    acknowledged TINYINT(1) NOT NULL DEFAULT 0,
    INDEX idx_odds_alert_records_created (created_at),
    INDEX idx_odds_alert_records_type (alert_type)
);

CREATE TABLE IF NOT EXISTS odds_collection_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_key VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    message TEXT NULL,
    started_at BIGINT NOT NULL,
    finished_at BIGINT NULL,
    records_count INT NOT NULL DEFAULT 0,
    INDEX idx_odds_collection_logs_source_time (source_key, started_at)
);

CREATE TABLE IF NOT EXISTS odds_data_source_configs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_key VARCHAR(32) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    username VARCHAR(128) NULL,
    password VARCHAR(256) NULL,
    query_keyword VARCHAR(128) NULL,
    interval_seconds INT NOT NULL DEFAULT 60,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_odds_data_source_configs_source (source_key)
);
