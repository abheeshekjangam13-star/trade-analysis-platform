-- Trades a backtest left out because the capital free at their entry could not buy a lot (see CapitalFit).
-- Older backtests ran without that limit, so they show 0.
ALTER TABLE backtest_result ADD COLUMN skipped_trades INT NOT NULL DEFAULT 0;
