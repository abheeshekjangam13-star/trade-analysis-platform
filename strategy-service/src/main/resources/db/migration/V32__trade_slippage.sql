-- The slippage allowance a backtest trade paid, in option points per side: bought that much above the market price and
-- sold that much below, deducted from its P&L (see OptionPrices). Null = none (rows before this column).
ALTER TABLE nifty_trade_log ADD COLUMN slippage DOUBLE PRECISION;
