package com.tradeanalysis.strategy.trading;

/** OFF: nothing is sent. PAPER: orders are simulated (filled at the option's last price). LIVE: orders go to Kite. */
public enum TradingMode { OFF, PAPER, LIVE }
