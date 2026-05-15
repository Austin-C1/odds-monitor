ALTER TABLE auto_betting_intents
    ADD COLUMN active_dedupe_key VARCHAR(255) NULL AFTER dedupe_key;

UPDATE auto_betting_intents
SET active_dedupe_key = NULL;

UPDATE auto_betting_intents intent
JOIN (
    SELECT MIN(id) AS keep_id
    FROM auto_betting_intents
    WHERE status IN ('ready', 'placing', 'placed')
    GROUP BY dedupe_key
) active_intent ON intent.id = active_intent.keep_id
SET intent.active_dedupe_key = intent.dedupe_key;

CREATE UNIQUE INDEX uq_auto_betting_intents_active_dedupe
    ON auto_betting_intents (active_dedupe_key);
