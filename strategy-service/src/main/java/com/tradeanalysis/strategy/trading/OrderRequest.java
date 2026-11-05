package com.tradeanalysis.strategy.trading;

/** An order to place: e.g. NFO / NIFTY25N1123550CE / BUY / 130 / MIS / LIMIT / 120.5. */
public record OrderRequest(String exchange, String tradingsymbol, String transactionType, int quantity, String product,
                           String orderType, Double price, Double triggerPrice) {}
