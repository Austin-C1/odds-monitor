CREATE TABLE IF NOT EXISTS bookkeeping_crown_accounts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    username VARCHAR(128) NOT NULL,
    password VARCHAR(512) NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    timezone VARCHAR(32) NOT NULL DEFAULT 'GMT-4',
    last_login_status VARCHAR(32) NULL,
    last_login_message TEXT NULL,
    last_login_at BIGINT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_bookkeeping_crown_account_key (account_key),
    INDEX idx_bookkeeping_crown_accounts_enabled (enabled)
);

CREATE TABLE IF NOT EXISTS bookkeeping_crown_wagers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NULL,
    account_id BIGINT NOT NULL,
    business_date VARCHAR(10) NOT NULL,
    ticket_id VARCHAR(128) NOT NULL,
    wager_time BIGINT NULL,
    league_name VARCHAR(128) NULL,
    home_team VARCHAR(128) NULL,
    away_team VARCHAR(128) NULL,
    market_type VARCHAR(64) NULL,
    selection_name VARCHAR(128) NULL,
    odds_value DECIMAL(18, 8) NULL,
    stake_amount DECIMAL(18, 4) NOT NULL DEFAULT 0,
    win_loss_amount DECIMAL(18, 4) NOT NULL DEFAULT 0,
    currency VARCHAR(16) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'unknown',
    raw_payload_json TEXT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_bookkeeping_crown_wager_ticket (account_id, business_date, ticket_id),
    INDEX idx_bookkeeping_crown_wagers_date (business_date),
    INDEX idx_bookkeeping_crown_wagers_task (task_id)
);

CREATE TABLE IF NOT EXISTS bookkeeping_whatsapp_groups (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    chat_name VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_bookkeeping_whatsapp_group_key (group_key),
    INDEX idx_bookkeeping_whatsapp_groups_role (role)
);

CREATE TABLE IF NOT EXISTS bookkeeping_whatsapp_orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NULL,
    group_id BIGINT NULL,
    business_date VARCHAR(10) NOT NULL,
    order_key VARCHAR(128) NOT NULL,
    direction VARCHAR(32) NOT NULL,
    message_time BIGINT NULL,
    sender_name VARCHAR(128) NULL,
    raw_message TEXT NOT NULL,
    league_name VARCHAR(128) NULL,
    match_name VARCHAR(255) NULL,
    market_text VARCHAR(255) NULL,
    odds_value DECIMAL(18, 8) NULL,
    amount DECIMAL(18, 4) NULL,
    currency VARCHAR(16) NULL,
    parse_status VARCHAR(32) NOT NULL DEFAULT 'parsed',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_bookkeeping_whatsapp_order_key (business_date, order_key),
    INDEX idx_bookkeeping_whatsapp_orders_date (business_date),
    INDEX idx_bookkeeping_whatsapp_orders_task (task_id)
);

CREATE TABLE IF NOT EXISTS bookkeeping_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_key VARCHAR(64) NOT NULL,
    business_date VARCHAR(10) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at BIGINT NULL,
    finished_at BIGINT NULL,
    result_summary_json TEXT NULL,
    excel_path VARCHAR(512) NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_bookkeeping_task_key (task_key),
    INDEX idx_bookkeeping_tasks_date (business_date),
    INDEX idx_bookkeeping_tasks_status (status)
);

CREATE TABLE IF NOT EXISTS bookkeeping_reconciliation_results (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    crown_wager_id BIGINT NULL,
    whatsapp_order_id BIGINT NULL,
    match_status VARCHAR(32) NOT NULL,
    issue_type VARCHAR(64) NULL,
    amount_diff DECIMAL(18, 4) NULL,
    odds_diff DECIMAL(18, 8) NULL,
    profit_amount DECIMAL(18, 4) NOT NULL DEFAULT 0,
    notes TEXT NULL,
    created_at BIGINT NOT NULL,
    INDEX idx_bookkeeping_reconciliation_task (task_id),
    INDEX idx_bookkeeping_reconciliation_status (match_status)
);
