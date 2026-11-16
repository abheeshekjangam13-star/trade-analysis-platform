package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.indicator.Params;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Set;

/**
 * The month-end (monthly) expiry day of NIFTY and BANKNIFTY options: the last Tuesday of the month (the last Thursday
 * before September 2025), moved to the trading day before when it falls on an NSE holiday (e.g. 24 Nov 2026 → 23 Nov).
 * <p>
 * Strategy parameter {@code skipMonthEndExpiry} (false): true takes no trades on that day, in backtests and live
 * trading alike (every engine checks it next to skipDays, and the live runner reruns the engines). The holidays come
 * from trading.market-holidays (TradingDays hands them over at startup); without them only weekends move the day.
 */
public final class MonthEndExpiry {

    public static final String PARAM = "skipMonthEndExpiry";
    public static final String REASON = "Month-end expiry day (skipMonthEndExpiry)";
    /** NSE moved monthly expiries from Thursday to Tuesday from September 2025. */
    static final LocalDate TUESDAY_FROM = LocalDate.of(2025, 9, 1);

    private static volatile Set<LocalDate> holidays = Set.of();

    private MonthEndExpiry() {}

    /** NSE holidays (from TradingDays at startup). */
    public static void useHolidays(Set<LocalDate> nseHolidays) {
        holidays = nseHolidays == null ? Set.of() : Set.copyOf(nseHolidays);
    }

    /** The month-end expiry of {@code day}'s month. */
    public static LocalDate of(LocalDate day) {
        LocalDate last = day.with(TemporalAdjusters.lastInMonth(day.isBefore(TUESDAY_FROM) ? DayOfWeek.THURSDAY : DayOfWeek.TUESDAY));
        while (last.getDayOfWeek() == DayOfWeek.SATURDAY || last.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(last))
            last = last.minusDays(1);
        return last;
    }

    public static boolean is(LocalDate day) {
        return of(day).equals(day);
    }

    /** Whether the strategy asks to skip month-end expiry days. */
    public static boolean enabled(Params params) {
        String v = params.stringOr(PARAM, "false").trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "yes", "y", "1", "on" -> true;
            case "false", "no", "n", "0", "off", "" -> false;
            default -> throw new IllegalArgumentException(PARAM + " must be true or false: " + v);
        };
    }

    /** Whether this strategy takes no trades on {@code day}. */
    public static boolean skips(Params params, LocalDate day) {
        return enabled(params) && is(day);
    }
}
