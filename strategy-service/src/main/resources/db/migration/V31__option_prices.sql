-- Backtest trades priced with the real premium of the option live trading would have bought (OptionPrices) instead of
-- the index-move estimate. Null on trades still estimated: the contract had expired before its candles were stored, or
-- Kite wasn't connected.
ALTER TABLE nifty_trade_log ADD COLUMN option_symbol VARCHAR(40);
ALTER TABLE nifty_trade_log ADD COLUMN entry_premium DOUBLE PRECISION;
ALTER TABLE nifty_trade_log ADD COLUMN exit_premium DOUBLE PRECISION;

-- 1-minute candles of option contracts, kept once fetched from Kite: Kite has no history for a contract after it
-- expires, so these keep past backtest trades priced. One row per contract per minute (ts = the minute's start).
CREATE TABLE option_candle (
    tradingsymbol VARCHAR(40)                 NOT NULL,
    underlying    VARCHAR(16)                 NOT NULL,
    expiry        DATE                        NOT NULL,
    strike        INTEGER                     NOT NULL,
    option_type   VARCHAR(2)                  NOT NULL,
    trade_date    DATE                        NOT NULL,
    ts            TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    open          DOUBLE PRECISION            NOT NULL,
    high          DOUBLE PRECISION            NOT NULL,
    low           DOUBLE PRECISION            NOT NULL,
    close         DOUBLE PRECISION            NOT NULL,
    PRIMARY KEY (tradingsymbol, ts)
);
CREATE INDEX option_candle_lookup ON option_candle (underlying, option_type, strike, trade_date);
