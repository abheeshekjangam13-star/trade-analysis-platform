-- Pair stop: when two of a user's strategies bought the same option on the same signal minute and one is stopped out at
-- a loss, the other is closed too, at once (0) or once the index moves this many more points against it. Null = off.
ALTER TABLE app_user ADD COLUMN pair_stop_points INTEGER;
-- The pair stop a portfolio report applied (null = off), as max_day_loss records the day loss it applied.
ALTER TABLE portfolio_report ADD COLUMN pair_stop_points INTEGER;
