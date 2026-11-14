-- Live trading only for users an admin picked (e.g. the admin and immediate family sharing the static IP). While
-- live_restricted is on, everyone else is refused new live orders and LIVE switches with live_restricted_message.
-- Exits are never blocked. Admins start allowed; the admin ticks the other users on the Admin page.
ALTER TABLE trading_control ADD COLUMN live_restricted BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE trading_control ADD COLUMN live_restricted_message VARCHAR(300);
ALTER TABLE app_user ADD COLUMN live_access BOOLEAN DEFAULT FALSE NOT NULL;

UPDATE app_user SET live_access = TRUE
WHERE id IN (SELECT ur.user_id FROM app_user_role ur JOIN app_role r ON r.id = ur.role_id WHERE r.name = 'ADMIN');
