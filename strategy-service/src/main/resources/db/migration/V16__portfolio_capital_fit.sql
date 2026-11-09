-- Portfolio reports fit all of a portfolio's trades to one capital pool (see PortfolioService.run).
-- requested_lots: the lots a strategy asked for, before the capital limit. Older trades have only the lots they were
-- taken with, so they start from those.
ALTER TABLE nifty_trade_log ADD COLUMN requested_lots INT NOT NULL DEFAULT 0;
UPDATE nifty_trade_log SET requested_lots = lots;

-- What the portfolio's own fit used: trades left out for lack of capital, and the capital in use at once.
ALTER TABLE portfolio_report ADD COLUMN skipped_trades INT NOT NULL DEFAULT 0;
ALTER TABLE portfolio_report ADD COLUMN peak_capital_used DOUBLE PRECISION;
ALTER TABLE portfolio_report ADD COLUMN peak_capital_on DATE;
ALTER TABLE portfolio_report ADD COLUMN avg_capital_used DOUBLE PRECISION;
ALTER TABLE portfolio_report ADD COLUMN max_capital_used DOUBLE PRECISION;
ALTER TABLE portfolio_report ADD COLUMN iv_assumed BOOLEAN;
