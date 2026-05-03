DELETE FROM copy_trading;

ALTER TABLE copy_trading
    ADD COLUMN follow_settings_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER enabled;

CREATE TABLE leader_copy_trading_control (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    leader_id BIGINT NOT NULL,
    auto_pause_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    paused_reason VARCHAR(255) NULL,
    last_peak_pnl DECIMAL(20, 8) NOT NULL DEFAULT 0.00000000,
    current_pnl DECIMAL(20, 8) NOT NULL DEFAULT 0.00000000,
    current_drawdown_percent DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    auto_paused_at BIGINT NULL,
    last_evaluated_at BIGINT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_leader_copy_trading_control_leader_id (leader_id)
) COMMENT='Leader 跟单分组控制';

CREATE TABLE copy_trading_follow_rule (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    copy_trading_id BIGINT NOT NULL,
    min_leader_amount DECIMAL(20, 8) NOT NULL,
    max_leader_amount DECIMAL(20, 8) NULL,
    follow_amount DECIMAL(20, 8) NOT NULL,
    follow_max_amount DECIMAL(20, 8) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    KEY idx_copy_trading_follow_rule_copy_trading_id (copy_trading_id),
    CONSTRAINT fk_copy_trading_follow_rule_copy_trading
        FOREIGN KEY (copy_trading_id) REFERENCES copy_trading(id) ON DELETE CASCADE
) COMMENT='跟单金额范围规则';
