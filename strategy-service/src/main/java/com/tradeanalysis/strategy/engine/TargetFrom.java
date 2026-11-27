package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.indicator.Params;

import java.util.Locale;

/**
 * Where targetPoints is measured from (parameter {@code targetFrom}): the entry, the 5-minute close the trade bought on
 * (ENTRY, the default), or the line it broke (LINE: yesterday's high / low, R1 / S1, the opening range's high / low).
 * With LINE, a trade that buys 20 points past the line with a 60-point target aims 40 points from its entry, so the
 * target is the same price whatever candle it buys on. A trade that buys at or past that target has no profit left to
 * aim for and is not taken ({@link #noRoom}).
 */
public enum TargetFrom {
    ENTRY, LINE;

    public static final String PARAM = "targetFrom";

    public static TargetFrom of(Params p) {
        String v = p.stringOr(PARAM, "").trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty()) return ENTRY;
        try { return valueOf(v); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("targetFrom must be ENTRY or LINE: " + v); }
    }

    /** For the engines with no fixed line (EMA cross, gap fill): LINE is refused with a readable message. */
    public static void needsALine(Params p, String strategyName) {
        if (of(p) == LINE)
            throw new IllegalArgumentException("Target from the line needs a strategy that buys at a fixed line; the "
                    + strategyName + " strategy has none. Measure the target from the entry instead.");
    }

    /** The target price: {@code points} in the trade's favour from the entry or from the line. */
    public double target(boolean call, double entry, double line, double points) {
        double from = this == LINE ? line : entry;
        return call ? from + points : from - points;
    }

    /** True when a target from the line is already reached at entry: the trade is not taken. */
    public boolean noRoom(boolean call, double entry, double line, double points) {
        if (this != LINE || points <= 0) return false;
        double t = target(call, entry, line, points);
        return call ? entry >= t : entry <= t;
    }

    /** Why a trade was not taken, for the day report. */
    static String noRoomReason(double entry, double line, double points) {
        return String.format("Closed %.2f, %.0f points past the line %.2f: already beyond the %.0f-point target from the line",
                entry, Math.abs(entry - line), line, points);
    }
}
