ALTER TABLE auto_betting_intents
    ADD COLUMN betting_mode VARCHAR(16) NOT NULL DEFAULT 'prematch' AFTER signal_source,
    ADD COLUMN match_phase VARCHAR(16) NOT NULL DEFAULT 'prematch' AFTER betting_mode;

UPDATE auto_betting_intents
SET betting_mode = 'live',
    match_phase = 'live'
WHERE status = 'ready'
  AND (
      LOWER(dedupe_key) LIKE '%:live:%'
      OR LOWER(match_title) LIKE '%live%'
  );
