-- A portfolio report's own version of each trade it combined: the lots it could afford from the shared capital, the
-- P&L at those lots, trades it left out (CAPITAL) or the max day loss kept out (MAX_DAY_LOSS), and exits the max day
-- loss forced. The backtests' own trades stay as each backtest made them, so they always add up to its stored result.
CREATE TABLE portfolio_trade (
    portfolio_report_id BIGINT                      NOT NULL REFERENCES portfolio_report (id) ON DELETE CASCADE,
    trade_log_id        BIGINT                      NOT NULL REFERENCES nifty_trade_log (id) ON DELETE CASCADE,
    lots                INTEGER                     NOT NULL,
    estimated_pnl       DOUBLE PRECISION            NOT NULL,
    skip_reason         VARCHAR(40),
    exit_timestamp      TIMESTAMP(6) WITH TIME ZONE,
    exit_spot_price     DOUBLE PRECISION,
    exit_reason         VARCHAR(255),
    option_symbol       VARCHAR(40),
    entry_premium       DOUBLE PRECISION,
    exit_premium        DOUBLE PRECISION,
    slippage            DOUBLE PRECISION,
    PRIMARY KEY (portfolio_report_id, trade_log_id)
);

-- Reports made before this stored their version on the backtests' trades themselves: copy it here, so those reports
-- still show what they counted.
INSERT INTO portfolio_trade (portfolio_report_id, trade_log_id, lots, estimated_pnl, skip_reason, exit_timestamp,
                             exit_spot_price, exit_reason, option_symbol, entry_premium, exit_premium, slippage)
SELECT DISTINCT i.portfolio_report_id, t.id, t.lots, t.estimated_pnl, t.skip_reason, t.exit_timestamp,
       t.exit_spot_price, t.exit_reason, t.option_symbol, t.entry_premium, t.exit_premium, t.slippage
FROM portfolio_report_item i
JOIN nifty_trade_log t ON t.backtest_result_id = i.backtest_result_id;
