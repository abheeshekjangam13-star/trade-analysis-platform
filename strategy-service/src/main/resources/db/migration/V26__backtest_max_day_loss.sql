-- Backtests and portfolio reports apply the user's max day loss the way live trading does (see DayLossStop): the
-- limit they ran with (null = none set) and on how many days it was reached. Older runs had no such stop.
ALTER TABLE backtest_result ADD COLUMN max_day_loss DOUBLE PRECISION;
ALTER TABLE backtest_result ADD COLUMN day_loss_stops INT NOT NULL DEFAULT 0;
ALTER TABLE portfolio_report ADD COLUMN max_day_loss DOUBLE PRECISION;
ALTER TABLE portfolio_report ADD COLUMN day_loss_stops INT NOT NULL DEFAULT 0;
