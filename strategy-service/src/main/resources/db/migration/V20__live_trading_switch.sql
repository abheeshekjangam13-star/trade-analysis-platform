-- Live trading on/off for the whole platform, switched by an admin on the Trading page (it used to be the
-- TRADING_LIVE_ENABLED server setting). Off by default: no live orders until an admin turns it on.
ALTER TABLE trading_control ADD COLUMN live_enabled BOOLEAN NOT NULL DEFAULT FALSE;
