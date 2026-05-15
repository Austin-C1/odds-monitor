ALTER TABLE auto_betting_intents
    ADD COLUMN crown_history_verified BOOLEAN NOT NULL DEFAULT FALSE AFTER reject_reason,
    ADD COLUMN crown_history_checked_at BIGINT NULL AFTER crown_history_verified,
    ADD COLUMN crown_bet_reference VARCHAR(128) NULL AFTER crown_history_checked_at;

CREATE INDEX idx_auto_betting_intents_verified_placed
    ON auto_betting_intents (status, crown_history_verified, created_at);
