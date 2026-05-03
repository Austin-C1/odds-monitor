ALTER TABLE odds_markets
    ADD COLUMN platform_match_id BIGINT NULL AFTER match_id;

UPDATE odds_markets
SET platform_match_id = match_id
WHERE platform_match_id IS NULL;

CREATE INDEX idx_odds_markets_platform_match ON odds_markets (platform_match_id, source_key);

ALTER TABLE odds_collection_logs
    ADD COLUMN match_count INT NOT NULL DEFAULT 0 AFTER records_count,
    ADD COLUMN market_count INT NOT NULL DEFAULT 0 AFTER match_count,
    ADD COLUMN empty_market_count INT NOT NULL DEFAULT 0 AFTER market_count,
    ADD COLUMN failure_reason TEXT NULL AFTER empty_market_count;
