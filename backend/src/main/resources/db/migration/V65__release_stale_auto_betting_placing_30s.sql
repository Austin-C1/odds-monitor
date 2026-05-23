UPDATE auto_betting_intents
SET status = 'rejected',
    reject_reason = 'crown_execution_timeout',
    active_dedupe_key = NULL,
    updated_at = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
WHERE status = 'placing'
  AND updated_at < CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED) - 30000;
