UPDATE auto_betting_intents
SET active_dedupe_key = NULL
WHERE status <> 'placed'
   OR crown_history_verified = FALSE;

UPDATE auto_betting_intents intent
JOIN (
    SELECT MIN(id) AS keep_id
    FROM auto_betting_intents
    WHERE status = 'placed'
      AND crown_history_verified = TRUE
    GROUP BY dedupe_key
) placed_intent ON intent.id = placed_intent.keep_id
SET intent.active_dedupe_key = intent.dedupe_key;
