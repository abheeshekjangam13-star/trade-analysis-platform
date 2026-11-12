-- A portfolio report pools its strategies' trades in one capital; a trade the free capital couldn't buy at its entry
-- is left out of the report. The trade stays stored, marked with why it was left out (CAPITAL), so the Trades page can
-- show it as skipped instead of counting it.
ALTER TABLE nifty_trade_log ADD COLUMN skip_reason VARCHAR(40);

-- The capital that would have taken every trade of a portfolio report at the lots its strategies asked for: the most
-- option premium open at once. Shown next to the skipped trades so users know what capital fits them all.
ALTER TABLE portfolio_report ADD COLUMN capital_to_fit_all DOUBLE PRECISION;
