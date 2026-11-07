package com.tradeanalysis.strategy.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A strategy can't be edited or deleted right now, with a {@link Reason} the UI turns into clear next steps
 * (e.g. a "Turn off trading" button). Sent as 409 with {@code code} in the error body.
 */
public class StrategyBlockedException extends ResponseStatusException {

    public enum Reason {
        /** The caller has it on PAPER/LIVE: they can turn it OFF themselves. */
        TRADING_ON,
        /** Someone else (an admin) has it on PAPER/LIVE. */
        TRADING_ON_BY_OTHERS,
        /** A paper/live position is open: wait until it exits, then turn trading off. */
        OPEN_POSITION,
        /** Part of a portfolio: delete the portfolio first. */
        IN_PORTFOLIO,
        /** Real (LIVE) trades are on record and are kept. */
        HAS_LIVE_TRADES
    }

    private final Reason code;

    public StrategyBlockedException(Reason code, String message) {
        super(HttpStatus.CONFLICT, message);
        this.code = code;
    }

    public Reason code() { return code; }
}
