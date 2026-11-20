package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.indicator.Params;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Narrow-range day filter for the CPR and opening-range modes, with the same rules as the previous-day engine's:
 * with narrowRangeDays N, trade only after an NR-N day, when yesterday's high-low range was the smallest of the last
 * N sessions (yesterday included; NR7 = 7, NR4 = 4). With excludeNarrowRangeDays M (above N), skip the day when
 * yesterday was also an NR-M day.
 *
 * Off (both 0) by default: a strategy without these parameters trades exactly as before.
 */
record NarrowRange(int days, int excludeDays) {

    static NarrowRange from(Params p) {
        int nr = p.intOr("narrowRangeDays", 0), nrExclude = p.intOr("excludeNarrowRangeDays", 0);
        if (nr != 0 && (nr < 2 || nr > 20))
            throw new IllegalArgumentException("narrowRangeDays must be 0 (off) or 2-20: " + nr);
        if (nrExclude != 0 && nr == 0)
            throw new IllegalArgumentException("excludeNarrowRangeDays needs narrowRangeDays");
        if (nrExclude != 0 && (nrExclude <= nr || nrExclude > 20))
            throw new IllegalArgumentException("excludeNarrowRangeDays must be above narrowRangeDays and at most 20: " + nrExclude);
        return new NarrowRange(nr, nrExclude);
    }

    boolean on() { return days > 0; }

    /** Trading sessions of history needed before the first trading day (0 when off). */
    int sessionsNeeded() { return on() ? Math.max(days, excludeDays) : 0; }

    /**
     * Why the filter keeps {@code dates[i]} from trading, as a day report, or null when it may trade (always null
     * when off).
     */
    NiftyOpeningBreakoutEngine.DayReport block(Map<LocalDate, List<Candle>> byDay, List<LocalDate> dates, int i, Double gap) {
        if (!on()) return null;
        LocalDate date = dates.get(i);
        int needed = sessionsNeeded();
        if (i < needed)
            return new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_DATA",
                    "Fewer than " + needed + " previous sessions for the narrow-range check", 0);
        double yRange = range(byDay.get(dates.get(i - 1)));
        if (yRange > smallestRange(byDay, dates, i, days))
            return new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE",
                    String.format("Yesterday's range %.2f was not the smallest of the last %d sessions (not NR%d)", yRange, days, days), 0);
        if (excludeDays > 0 && yRange <= smallestRange(byDay, dates, i, excludeDays))
            return new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE",
                    "Yesterday was also an NR" + excludeDays + " day (excluded)", 0);
        return null;
    }

    private static double range(List<Candle> day) {
        double hi = day.stream().mapToDouble(c -> c.high().doubleValue()).max().orElseThrow();
        double lo = day.stream().mapToDouble(c -> c.low().doubleValue()).min().orElseThrow();
        return hi - lo;
    }

    /** Smallest high-low range of the {@code n} sessions before {@code dates[i]} (yesterday and the n-1 before it). */
    private static double smallestRange(Map<LocalDate, List<Candle>> byDay, List<LocalDate> dates, int i, int n) {
        double min = Double.MAX_VALUE;
        for (int k = i - n; k < i; k++) min = Math.min(min, range(byDay.get(dates.get(k))));
        return min;
    }
}
