-- Nicknames are intentionally not unique. Keep lookup bounded and indexed.
CREATE INDEX idx_cloud_accounts_account_name ON cloud_accounts(account_name);
