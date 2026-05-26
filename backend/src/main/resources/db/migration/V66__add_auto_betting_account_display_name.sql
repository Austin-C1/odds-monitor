ALTER TABLE auto_betting_intents
    ADD COLUMN account_display_name VARCHAR(128) NULL AFTER account_key;
