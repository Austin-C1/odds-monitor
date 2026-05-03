ALTER TABLE copy_trading_leaders
    ADD COLUMN custom_group VARCHAR(100) NULL AFTER category;

ALTER TABLE wallet_accounts
    ADD COLUMN remark TEXT NULL AFTER account_name;
