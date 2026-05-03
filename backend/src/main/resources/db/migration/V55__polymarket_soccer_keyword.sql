UPDATE odds_data_source_configs
SET query_keyword = 'soccer',
    updated_at = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
WHERE source_key = 'polymarket'
  AND (query_keyword IS NULL OR LOWER(TRIM(query_keyword)) = 'football');
