package com.tradeanalysis.common.market;

import java.util.Locale;

/**
 * The index a strategy trades. Everything index-specific lives here: its candle symbol, the name of its option
 * contracts on Kite, the strike step, the default lot size and the expiry rule. NIFTY is the default, so a strategy
 * without an underlying trades NIFTY exactly as before.
 *
 * NIFTY has weekly expiries (Tuesday from Sept 2025). BANKNIFTY has monthly expiries only (the last Tuesday of the
 * month, moved earlier for holidays), so its premiums are higher and its points move about 2 to 2.5 times NIFTY's.
 */
public enum Underlying {
    NIFTY("NIFTY 50", "NIFTY", 50, 65, false),
    BANKNIFTY("NIFTY BANK", "BANKNIFTY", 100, 30, true);

    /** Name of the index candles (Kite's NSE index, e.g. "NIFTY 50"). */
    public final String indexSymbol;
    /** Name of the option contracts on NFO (e.g. "NIFTY" for NIFTY25N1123550CE). */
    public final String optionName;
    /** Strikes are this far apart; ATM is the spot rounded to this step. */
    public final int strikeStep;
    /** Default contracts per lot. */
    public final int defaultLotSize;
    /** Monthly expiry only (no weekly contracts). */
    public final boolean monthlyOnly;

    Underlying(String indexSymbol, String optionName, int strikeStep, int defaultLotSize, boolean monthlyOnly) {
        this.indexSymbol = indexSymbol; this.optionName = optionName; this.strikeStep = strikeStep;
        this.defaultLotSize = defaultLotSize; this.monthlyOnly = monthlyOnly;
    }

    /** The underlying named in a strategy's parameters ("NIFTY", "BANKNIFTY"; blank or unknown = NIFTY). */
    public static Underlying of(String name) {
        if (name == null || name.isBlank()) return NIFTY;
        String n = name.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        return switch (n) {
            case "BANKNIFTY", "BANK-NIFTY", "NIFTYBANK", "NIFTY_BANK", "BANK" -> BANKNIFTY;
            default -> NIFTY;
        };
    }

    /** ATM strike: the spot rounded to this underlying's step. */
    public int atm(double spot) {
        return (int) (Math.round(spot / strikeStep) * strikeStep);
    }

    /** The strike a breakout buys: CE one step below ATM, PE one step above. */
    public int strike(boolean call, double spot) {
        return call ? atm(spot) - strikeStep : atm(spot) + strikeStep;
    }
}
