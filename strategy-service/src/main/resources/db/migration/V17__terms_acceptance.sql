-- Sign-up requires accepting the Terms and Conditions: when and which version (the date on the Terms page).
ALTER TABLE app_user ADD COLUMN terms_accepted_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE app_user ADD COLUMN terms_version VARCHAR(20);

-- Accounts made before this count as having accepted the current terms.
UPDATE app_user SET terms_accepted_at = CURRENT_TIMESTAMP, terms_version = '2026-11-11';
