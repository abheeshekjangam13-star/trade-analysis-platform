-- One login at a time per user: each login stores a new session id here and puts it in the token. A token whose
-- session id isn't the user's latest is refused (by the gateway and strategy-service), so logging in on another
-- window or device logs the older ones out. Null = no login since this column was added: older tokens still work
-- until they expire or the user logs in again.
ALTER TABLE app_user ADD COLUMN session_id VARCHAR(36);
