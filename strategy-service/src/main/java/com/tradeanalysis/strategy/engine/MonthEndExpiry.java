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
 * trading alike (every engine checks it next to skipDays, and the live runner reruns the engines).
 * Strategy parameter {@code onlyMonthEndExpiry} (false): the opposite, true trades on that day only. The two can't
 * both be true. The holidays come
 * from trading.market-holidays (TradingDays hands them over at startup); without them only weekends move the day.
 */
public final class MonthEndExpiry {

    public static final String PARAM = "skipMonthEndExpiry";
    public static final String REASON = "Month-end expiry day (skipMonthEndExpiry)";
    public static final String ONLY_PARAM = "onlyMonthEndExpiry";
    public static final String ONLY_REASON = "Not the month-end expiry day (onlyMonthEndExpiry)";
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
        return flag(params, PARAM);
    }

    /** Whether the strategy trades on month-end expiry days only. */
    public static boolean only(Params params) {
        return flag(params, ONLY_PARAM);
    }

    /** Checks both settings: each true or false, and not both true. */
    public static void validate(Params params) {
        boolean skip = enabled(params), only = only(params);   // both read, so a bad value in either is reported
        if (skip && only)
            throw new IllegalArgumentException(PARAM + " and " + ONLY_PARAM + " can't both be true");
    }

    private static boolean flag(Params params, String key) {
        String v = params.stringOr(key, "false").trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "yes", "y", "1", "on" -> true;
            case "false", "no", "n", "0", "off", "" -> false;
            default -> throw new IllegalArgumentException(key + " must be true or false: " + v);
        };
    }

    /** Whether this strategy takes no trades on {@code day}: a skipped month-end expiry, or any other day when it trades only that one. */
    public static boolean skips(Params params, LocalDate day) {
        boolean me = is(day);
        return (enabled(params) && me) || (only(params) && !me);
    }

    /** Why {@link #skips} is true on {@code day}. */
    public static String reason(Params params, LocalDate day) {
        return is(day) ? REASON : ONLY_REASON;
    }
}
