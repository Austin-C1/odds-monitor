SET @now_ms = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000;

UPDATE odds_data_source_configs
SET query_keyword = NULL,
    updated_at = @now_ms
WHERE source_key = 'crown'
  AND enabled = 0
  AND username IS NULL
  AND password IS NULL
  AND TRIM(query_keyword) = 'https://m407.mos077.com/';
