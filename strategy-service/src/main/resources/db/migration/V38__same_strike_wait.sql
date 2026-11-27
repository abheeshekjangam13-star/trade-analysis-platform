-- Same-strike wait: when another of the user's strategies already bought the same option (CE/PE and strike) that day, a
-- second buy waits this many minutes after its signal before entering, and is dropped if the trade is over by then.
-- Null = off.
ALTER TABLE app_user ADD COLUMN same_strike_wait_minutes INTEGER;
-- The wait a portfolio report applied (null = off).
ALTER TABLE portfolio_report ADD COLUMN same_strike_wait_minutes INTEGER;
-- A report's version of a trade it delayed: the minutes waited and the index price it then bought at.
ALTER TABLE portfolio_trade ADD COLUMN entry_delay_minutes INTEGER;
ALTER TABLE portfolio_trade ADD COLUMN entry_spot_price DOUBLE PRECISION;
