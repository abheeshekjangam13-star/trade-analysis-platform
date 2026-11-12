package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.common.market.Underlying;

import java.time.*;
import java.util.*;

/**
 * Shared pieces of the one-trade-a-day engines with a fixed stop and a point target ({@link CprEngine},
 * {@link EmaCrossEngine}): the settings they all read and the exit walk on 1-minute candles.
 */
final class FixedStopTrade {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime EOD_EXIT_TIME = LocalTime.of(15, 20);
    static final LocalTime DEFAULT_ENTRY_UNTIL = LocalTime.of(14, 0);

    private FixedStopTrade() {}

    record Exit(Candle bar, double price, String reason) {}

    /**
     * Settings common to these engines. Parameters: lots (2), underlying, directions CE|PE|BOTH (BOTH), targetPoints
     * (0 = none), maxStopPoints (0 = no cap), entryUntil (14:00), skipDays (none), costPerTrade (0).
     */
    record Basics(int lots, int lotSize, Underlying underlying, boolean allowCe, boolean allowPe, double targetPoints,
                  double maxStopPoints, LocalTime entryUntil, Set<DayOfWeek> skipDays, double costPerTrade) {

        static Basics from(Params p, String strategyName) {
            String dirs = p.stringOr("directions", "BOTH").trim().toUpperCase(Locale.ROOT);
            if (dirs.isEmpty()) dirs = "BOTH";
            if (!Set.of("CE", "PE", "BOTH").contains(dirs))
                throw new IllegalArgumentException("The " + strategyName + " strategy trades CE, PE or BOTH: " + dirs);
            double target = p.doubleOr("targetPoints", 0), cap = p.doubleOr("maxStopPoints", 0);
            if (target < 0) throw new IllegalArgumentException("targetPoints can't be negative: " + target);
            if (cap < 0) throw new IllegalArgumentException("maxStopPoints can't be negative: " + cap);
            Underlying u = Underlying.of(p.stringOr("underlying", ""));
            return new Basics(p.intOr("lots", 2), u.defaultLotSize, u, !dirs.equals("PE"), !dirs.equals("CE"), target, cap,
                    time(p, "entryUntil", DEFAULT_ENTRY_UNTIL), days(p.stringOr("skipDays", "")), p.doubleOr("costPerTrade", 0));
        }

        /** The setup's stop, moved closer to entry when it is further away than maxStopPoints (CE below entry, PE above). */
        double capStop(boolean call, double entry, double stop) {
            if (maxStopPoints <= 0) return stop;
            return call ? Math.max(stop, entry - maxStopPoints) : Math.min(stop, entry + maxStopPoints);
        }
    }

    static LocalTime time(Params p, String name, LocalTime fallback) {
        String v = p.stringOr(name, "").trim();
        if (v.isEmpty()) return fallback;
        try { return LocalTime.parse(v); }
        catch (DateTimeException e) { throw new IllegalArgumentException(name + " must be HH:mm: " + v); }
    }

    static Set<DayOfWeek> days(String raw) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        if (raw == null || raw.isBlank()) return out;
        for (String s : raw.split(",")) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            String prefix = t.substring(0, Math.min(3, t.length()));
            out.add(Arrays.stream(DayOfWeek.values()).filter(x -> x.name().startsWith(prefix)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("skipDays has an unknown weekday: " + s)));
        }
        return out;
    }

    static LocalTime istTime(Candle c) { return c.timestamp().atZone(IST).toLocalTime(); }

    /**
     * Walks the 1-min candles after the 5-min entry candle: stop first, then target (when targetPoints > 0), else
     * square-off at 15:20 or at the last bar. With {@code closeAtEnd} false (live), a trade with no exit yet returns null.
     */
    static Exit exitFor(boolean call, double stop, double entryPx, double targetPoints, Candle entry, LocalDate date,
                        NavigableMap<Instant, Candle> oneMin, Instant now, boolean closeAtEnd) {
        Instant from = entry.timestamp().plus(Duration.ofMinutes(5));
        Instant to = date.atTime(EOD_EXIT_TIME).atZone(IST).toInstant();
        double target = call ? entryPx + targetPoints : entryPx - targetPoints;
        Candle last = null;
        for (Candle bar : oneMin.subMap(from, true, to, true).values()) {
            if (bar.timestamp().plus(Duration.ofMinutes(1)).isAfter(now)) break;   // still forming
            if (!bar.timestamp().isBefore(to)) return new Exit(bar, bar.open().doubleValue(), "EOD_SQUAREOFF");
            last = bar;
            double o = bar.open().doubleValue();
            if (call ? bar.low().doubleValue() <= stop : bar.high().doubleValue() >= stop)
                return new Exit(bar, call ? Math.min(o, stop) : Math.max(o, stop), "SL_HIT");
            if (targetPoints > 0 && (call ? bar.high().doubleValue() >= target : bar.low().doubleValue() <= target))
                return new Exit(bar, call ? Math.max(o, target) : Math.min(o, target), "TARGET_HIT");
        }
        if (!closeAtEnd && now.isBefore(to)) return null;
        if (last == null) return new Exit(entry, entry.close().doubleValue(), "EOD_SQUAREOFF");
        return new Exit(last, last.close().doubleValue(), "EOD_SQUAREOFF");
    }
}
