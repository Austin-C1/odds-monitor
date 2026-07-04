SET @now_ms = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000;

UPDATE odds_data_source_configs
SET query_keyword = NULL,
    updated_at = @now_ms
WHERE source_key = 'crown'
  AND enabled = 0
  AND username IS NULL
  AND password IS NULL
  AND TRIM(query_keyword) = 'https://m407.mos077.com/';

DROP TEMPORARY TABLE IF EXISTS tmp_pinnacle_platform_match_ids;
DROP TEMPORARY TABLE IF EXISTS tmp_pinnacle_market_ids;
DROP TEMPORARY TABLE IF EXISTS tmp_pinnacle_standard_match_ids;

CREATE TEMPORARY TABLE tmp_pinnacle_platform_match_ids AS
SELECT id
FROM odds_platform_matches
WHERE source_key = 'pinnacle';

CREATE TEMPORARY TABLE tmp_pinnacle_market_ids AS
SELECT id
FROM odds_markets
WHERE source_key = 'pinnacle'
   OR platform_match_id IN (SELECT id FROM tmp_pinnacle_platform_match_ids);

CREATE TEMPORARY TABLE tmp_pinnacle_standard_match_ids AS
SELECT DISTINCT match_id AS id
FROM odds_match_links
WHERE platform_match_id IN (SELECT id FROM tmp_pinnacle_platform_match_ids);

DELETE FROM odds_snapshots
WHERE source_key = 'pinnacle'
   OR market_id IN (SELECT id FROM tmp_pinnacle_market_ids);

DELETE FROM odds_markets
WHERE source_key = 'pinnacle'
   OR id IN (SELECT id FROM tmp_pinnacle_market_ids);

DELETE FROM odds_match_links
WHERE platform_match_id IN (SELECT id FROM tmp_pinnacle_platform_match_ids);

DELETE FROM odds_platform_matches
WHERE source_key = 'pinnacle';

DELETE FROM odds_matches
WHERE id IN (SELECT id FROM tmp_pinnacle_standard_match_ids)
  AND id NOT IN (SELECT match_id FROM odds_match_links);

DELETE FROM odds_collection_logs
WHERE source_key = 'pinnacle';

DELETE FROM odds_alert_records
WHERE source_key = 'pinnacle'
   OR LOWER(title) LIKE '%pinnacle%'
   OR LOWER(message) LIKE '%pinnacle%'
   OR title LIKE '%平博%'
   OR message LIKE '%平博%';

DELETE FROM odds_data_source_configs
WHERE source_key = 'pinnacle';

DELETE FROM system_config
WHERE config_key = 'odds_monitor.selected_leagues.pinnacle';

UPDATE system_config
SET config_value = JSON_ARRAY(),
    updated_at = @now_ms
WHERE config_key = 'odds_monitor.selected_leagues'
  AND (
      config_value IS NULL
      OR TRIM(config_value) = ''
      OR LOWER(config_value) LIKE '%pinnacle%'
      OR config_value LIKE '%平博%'
  );

DELETE FROM auto_betting_intents
WHERE reference_source_key = 'pinnacle'
   OR target_source_key = 'pinnacle';

DROP TEMPORARY TABLE IF EXISTS tmp_pinnacle_standard_match_ids;
DROP TEMPORARY TABLE IF EXISTS tmp_pinnacle_market_ids;
DROP TEMPORARY TABLE IF EXISTS tmp_pinnacle_platform_match_ids;
