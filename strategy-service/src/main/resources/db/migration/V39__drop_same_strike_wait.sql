-- The same-strike wait (V38) was removed: a real-engine test showed it cost more than it saved. V38 stays because it
-- has already run; this drops its columns again.
ALTER TABLE app_user DROP COLUMN same_strike_wait_minutes;
ALTER TABLE portfolio_report DROP COLUMN same_strike_wait_minutes;
ALTER TABLE portfolio_trade DROP COLUMN entry_delay_minutes;
ALTER TABLE portfolio_trade DROP COLUMN entry_spot_price;
