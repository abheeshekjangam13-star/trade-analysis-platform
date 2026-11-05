package com.tradeanalysis.strategy.trading;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A trading request that cannot be carried out (bad input, broker error, Kite not connected). */
public class TradingException extends ResponseStatusException {
    public TradingException(String message) { super(HttpStatus.BAD_REQUEST, message); }
    public TradingException(HttpStatus status, String message) { super(status, message); }
}
