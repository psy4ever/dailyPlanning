-- The existing login name becomes the stable, unique UID. Existing owner IDs,
-- password hashes, sessions, and business records are not modified.
ALTER TABLE cloud_accounts RENAME COLUMN username TO uid;
ALTER TABLE cloud_accounts ADD COLUMN account_name VARCHAR(80);
UPDATE cloud_accounts SET account_name = uid WHERE account_name IS NULL;
