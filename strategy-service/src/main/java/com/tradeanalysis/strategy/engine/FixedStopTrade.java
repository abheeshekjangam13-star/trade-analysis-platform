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

    /** How a trade ended: the bar and price, the reason, and the stop at that moment (moved by {@link Protect}). */
    record Exit(Candle bar, double price, String reason, double stop) {
        Exit(Candle bar, double price, String reason) { this(bar, price, reason, Double.NaN); }
    }

    /**
     * Optional stop protection for these engines, off unless trailAfterPoints is above 0. Once spot has moved
     * trailAfterPoints in the trade's favour (checked on completed 1-minute candles after the entry candle):
     * - breakevenOnTrail (false): the stop moves to the entry price, or breakevenLockPoints (0, below trailAfterPoints)
     *   beyond it in the trade's favour;
     * - trailMinutes (5; 0 = don't trail): after each completed block of that many 1-minute candles (aligned to 09:15),
     *   the stop follows the block's low (CE) or high (PE), never moving back.
     * Same setting names and rules as the opening-range engine (see {@link TrailingStopLoss}).
     */
    record Protect(double afterPoints, int trailMinutes, boolean breakeven, double lockPoints) {
        static final Protect NONE = new Protect(0, 0, false, 0);
        static final int DEFAULT_TRAIL_MINUTES = 5;

        static Protect from(Params p) {
            double after = p.doubleOr("trailAfterPoints", 0);
            if (after < 0) throw new IllegalArgumentException("trailAfterPoints can't be negative: " + after);
            if (p.doubleOr("trailAfterR", 0) > 0)
                throw new IllegalArgumentException("This strategy kind trails after points (trailAfterPoints), not after R");
            if (after == 0) return NONE;
            int minutes = p.intOr("trailMinutes", DEFAULT_TRAIL_MINUTES);
            if (minutes < 0 || minutes > 60) throw new IllegalArgumentException("trailMinutes must be 0 (don't trail) to 60: " + minutes);
            boolean breakeven = Boolean.parseBoolean(p.stringOr("breakevenOnTrail", "false").trim());
            double lock = p.doubleOr("breakevenLockPoints", 0);
            if (lock < 0 || lock >= after)
                throw new IllegalArgumentException("breakevenLockPoints must be 0 or more and below trailAfterPoints: " + lock);
            if (minutes == 0 && !breakeven)
                throw new IllegalArgumentException("With trailMinutes 0 (don't trail), set breakevenOnTrail, or trailAfterPoints does nothing");
            return new Protect(after, minutes, breakeven, lock);
        }

        boolean on() { return afterPoints > 0; }

        /** The trade's stop: fixed when off, otherwise one that waits for afterPoints and then protects as set. */
        TrailingStopLoss stop(boolean call, double stop, double entryPx, Candle entry) {
            var dir = call ? TrailingStopLoss.Direction.BULLISH : TrailingStopLoss.Direction.BEARISH;
            // trailMinutes 0: blocks of 1,000 minutes never complete inside a session, so only the breakeven move applies.
            var s = TrailingStopLoss.openAt(dir, stop, entry.timestamp(), trailMinutes > 0 ? trailMinutes : 1000);
            return on() ? s.trailOnlyAfter(entryPx, afterPoints, breakeven, lockPoints) : s;   // off: never fed a candle, stays put
        }
    }

    /**
     * Settings common to these engines. Parameters: lots (2), underlying, directions CE|PE|BOTH (BOTH), targetPoints
     * (0 = none), targetFrom ENTRY|LINE (ENTRY, {@link TargetFrom}), maxStopPoints (0 = no cap), entryUntil (14:00), skipDays (none), costPerTrade (0), and the stop
     * protection of {@link Protect}.
     */
    record Basics(int lots, int lotSize, Underlying underlying, boolean allowCe, boolean allowPe, double targetPoints,
                  double maxStopPoints, LocalTime entryUntil, Set<DayOfWeek> skipDays, double costPerTrade, Protect protect,
                  TargetFrom targetFrom) {

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
                    time(p, "entryUntil", DEFAULT_ENTRY_UNTIL), days(p.stringOr("skipDays", "")), p.doubleOr("costPerTrade", 0),
                    Protect.from(p), TargetFrom.of(p));
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
        return exitFor(call, stop, entryPx, call ? entryPx + targetPoints : entryPx - targetPoints, targetPoints > 0,
                Protect.NONE, entry, date, oneMin, now, closeAtEnd, null);
    }

    /**
     * As above, with the target as a price ({@code hasTarget} false = none) and the stop moved by {@code protect}.
     * {@code open}, when given, receives the stop as it stands for a trade that is still open (live).
     */
    static Exit exitFor(boolean call, double stop, double entryPx, double target, boolean hasTarget, Protect protect,
                        Candle entry, LocalDate date, NavigableMap<Instant, Candle> oneMin, Instant now, boolean closeAtEnd,
                        java.util.function.DoubleConsumer open) {
        Instant from = entry.timestamp().plus(Duration.ofMinutes(5));
        Instant to = date.atTime(EOD_EXIT_TIME).atZone(IST).toInstant();
        TrailingStopLoss sl = protect.stop(call, stop, entryPx, entry);
        Candle last = null;
        for (Candle bar : oneMin.subMap(from, true, to, true).values()) {
            if (bar.timestamp().plus(Duration.ofMinutes(1)).isAfter(now)) break;   // still forming
            if (!bar.timestamp().isBefore(to)) return new Exit(bar, bar.open().doubleValue(), "EOD_SQUAREOFF", sl.currentStop());
            last = bar;
            double o = bar.open().doubleValue();
            if (sl.isHitWithin(bar)) return new Exit(bar, sl.fillPrice(bar), "SL_HIT", sl.currentStop());
            if (hasTarget && (call ? bar.high().doubleValue() >= target : bar.low().doubleValue() <= target))
                return new Exit(bar, call ? Math.max(o, target) : Math.min(o, target), "TARGET_HIT", sl.currentStop());
            if (protect.on() && sl.onCandle(bar, now) == TrailingStopLoss.UpdateOutcome.MISSING_CANDLES) {
                // A hole in the 1-min data: resume from this bar.
                sl.resyncTo(bar.timestamp().minus(TrailingStopLoss.TRAIL_CANDLE));
                sl.onCandle(bar, now);
            }
        }
        if (!closeAtEnd && now.isBefore(to)) {
            if (open != null) open.accept(sl.currentStop());
            return null;
        }
        if (last == null) return new Exit(entry, entry.close().doubleValue(), "EOD_SQUAREOFF", sl.currentStop());
        return new Exit(last, last.close().doubleValue(), "EOD_SQUAREOFF", sl.currentStop());
    }
}
