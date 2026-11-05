-- Self sign-up with admin approval, and subscription plans (FREE view-only, GOLD backtests + paper, PREMIUM live).
ALTER TABLE app_user ADD COLUMN phone VARCHAR(20);
ALTER TABLE app_user ADD COLUMN plan VARCHAR(20) DEFAULT 'FREE' NOT NULL;
ALTER TABLE app_user ADD COLUMN requested_plan VARCHAR(20);
ALTER TABLE app_user ADD COLUMN plan_expires_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE app_user ADD COLUMN approved_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE app_user ADD COLUMN approved_by_user_id BIGINT;
ALTER TABLE app_user ADD COLUMN review_note VARCHAR(500);
CREATE UNIQUE INDEX ux_app_user_phone ON app_user (phone);

INSERT INTO app_role (name, description) VALUES ('PREMIUM', 'Premium plan: Gold plus live trading (once each user connects their own Kite account)');
UPDATE app_role SET description = 'Gold plan: create strategies, run backtests and paper trading' WHERE name = 'TRADER';
UPDATE app_role SET description = 'Free plan: view strategies, backtests and reports' WHERE name = 'VIEWER';

-- Existing users keep what they could do: admins PREMIUM, traders GOLD, viewers FREE.
UPDATE app_user SET plan = 'PREMIUM' WHERE id IN (
    SELECT ur.user_id FROM app_user_role ur JOIN app_role r ON r.id = ur.role_id WHERE r.name = 'ADMIN');
UPDATE app_user SET plan = 'GOLD' WHERE plan = 'FREE' AND id IN (
    SELECT ur.user_id FROM app_user_role ur JOIN app_role r ON r.id = ur.role_id WHERE r.name = 'TRADER');
