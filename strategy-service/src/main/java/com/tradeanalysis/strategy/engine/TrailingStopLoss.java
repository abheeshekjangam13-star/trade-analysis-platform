package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Stop-loss on NIFTY spot for one open Opening-Breakout position. Shared by the backtest and
 * the live monitor so both apply identical rules.
 *
 * - Initial SL = midpoint of the 09:15 reference candle, (refHigh + refLow) / 2, for both directions,
 *   fixed at the moment the order is placed (close of the 5-minute breakout candle).
 * - From then on, after each completed trailing block of {@code trailMinutes} 1-minute candles
 *   (blocks aligned to 09:15 IST; default 1 minute):
 *     BULLISH: sl = max(sl, block low)
 *     BEARISH: sl = min(sl, block high)
 *   so the stop never moves against the position. Only minutes after the order count towards a
 *   block. Entries still use 5-minute candles.
 * - Candles that are still forming, already processed, or not contiguous with the last processed
 *   candle are never applied. A gap must be filled (recover the missing candles) before trailing
 *   resumes, otherwise the stop could be computed from a stale candle.
 * - Optional (backtest only, see {@link #trailOnlyAfter}): completed blocks are ignored until spot has
 *   moved a set number of points in favour of the entry; optionally the stop then jumps to the entry.
 * - Hit: BULLISH when spot ≤ sl, BEARISH when spot ≥ sl.
 *
 * Candle timestamps are bar start times (Kite convention); a 1-minute bar is complete at start + 1 min.
 */
public final class TrailingStopLoss {

    private static final Logger log = LoggerFactory.getLogger(TrailingStopLoss.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final Duration ENTRY_CANDLE = Duration.ofMinutes(5);
    public static final Duration TRAIL_CANDLE = Duration.ofMinutes(1);

    public enum Direction { BULLISH, BEARISH }

    /** ACCUMULATED: candle consumed into a trailing block that has not completed yet. */
    public enum UpdateOutcome { UPDATED, UNCHANGED, ACCUMULATED, IGNORED_INCOMPLETE, IGNORED_DUPLICATE, MISSING_CANDLES }

    private static final int SESSION_OPEN_MINUTE = 9 * 60 + 15;

    private final Direction direction;
    private final int trailMinutes;
    private double currentStop;
    // Last minute of the last applied block (initially the breakout's last minute). This is what gets
    // persisted, so a restart replays any partially accumulated block instead of losing it.
    private Instant lastProcessedCandle;
    private Instant lastSeenCandle;        // last 1-min candle consumed, including the open block
    private double blockHigh = Double.NaN, blockLow = Double.NaN;
    // Delayed trailing: until armed, completed blocks do not move the stop. Armed from the start by default.
    private boolean armed = true;
    private double entryPrice = Double.NaN, armAfterPoints, bestFavourable;
    private boolean breakevenOnArm;
    private double breakevenLock;           // points of profit locked when moving the stop to breakeven

    private TrailingStopLoss(Direction direction, double currentStop, Instant lastProcessedCandle, int trailMinutes) {
        if (trailMinutes < 1) throw new IllegalArgumentException("trailMinutes must be >= 1: " + trailMinutes);
        this.direction = direction;
        this.currentStop = currentStop;
        this.lastProcessedCandle = lastProcessedCandle;
        this.lastSeenCandle = lastProcessedCandle;
        this.trailMinutes = trailMinutes;
    }

    public static double initialStop(double refHigh, double refLow) {
        return (refHigh + refLow) / 2.0;
    }

    /**
     * Opens a stop at the reference-candle midpoint for an entry on the 5-minute breakout candle
     * starting at {@code entryCandleStart}. Every 1-minute candle inside the breakout candle counts as
     * processed, so none of them feeds the stop; trailing starts with the first 1-minute candle after
     * the order is placed.
     */
    public static TrailingStopLoss open(Direction direction, double refHigh, double refLow, Instant entryCandleStart) {
        return open(direction, refHigh, refLow, entryCandleStart, 1);
    }

    public static TrailingStopLoss open(Direction direction, double refHigh, double refLow,
                                        Instant entryCandleStart, int trailMinutes) {
        Instant lastMinuteOfBreakout = entryCandleStart.plus(ENTRY_CANDLE).minus(TRAIL_CANDLE);
        TrailingStopLoss sl = new TrailingStopLoss(direction, initialStop(refHigh, refLow), lastMinuteOfBreakout, trailMinutes);
        log.info("SL_INIT direction={} refHigh={} refLow={} initialSl={} entryCandle={} trailMinutes={}",
                direction, refHigh, refLow, sl.currentStop, ist(entryCandleStart), trailMinutes);
        return sl;
    }

    /**
     * Keeps the stop where it is until a completed 1-minute candle after the order has traded
     * {@code points} beyond {@code entryPrice} in the position's favour; trailing then proceeds as usual.
     * With {@code breakeven}, the stop is also lifted to the entry price at that moment.
     * Not persisted by {@link #restore}, so the live monitor does not use it.
     */
    public TrailingStopLoss trailOnlyAfter(double entryPrice, double points, boolean breakeven) {
        return trailOnlyAfter(entryPrice, points, breakeven, 0);
    }

    /**
     * As {@link #trailOnlyAfter(double, double, boolean)}, but with {@code breakeven} the stop goes to the
     * entry price moved {@code lockPoints} in the position's favour (BULLISH entry + lock, BEARISH entry − lock),
     * so a trade that comes back still closes in profit.
     */
    public TrailingStopLoss trailOnlyAfter(double entryPrice, double points, boolean breakeven, double lockPoints) {
        if (points <= 0) return this;
        if (lockPoints < 0 || lockPoints >= points)
            throw new IllegalArgumentException("lockPoints must be >= 0 and below the trigger: " + lockPoints);
        this.breakevenLock = lockPoints;
        this.entryPrice = entryPrice;
        this.armAfterPoints = points;
        this.breakevenOnArm = breakeven;
        this.bestFavourable = 0;
        this.armed = false;
        return this;
    }

    public boolean isTrailing() { return armed; }

    /** Moves the stop closer to price (never further away): BULLISH max(stop, level), BEARISH min(stop, level). */
    public TrailingStopLoss tightenTo(double level) {
        currentStop = direction == Direction.BULLISH ? Math.max(currentStop, level) : Math.min(currentStop, level);
        return this;
    }

    /** Rebuilds a stop from persisted state; never resets to the initial value. */
    public static TrailingStopLoss restore(Direction direction, double currentStop, Instant lastProcessedCandle) {
        return restore(direction, currentStop, lastProcessedCandle, 1);
    }

    public static TrailingStopLoss restore(Direction direction, double currentStop, Instant lastProcessedCandle,
                                           int trailMinutes) {
        return new TrailingStopLoss(direction, currentStop, lastProcessedCandle, trailMinutes);
    }

    /** Applies one completed 1-minute candle. {@code now} decides whether the candle has closed. */
    public UpdateOutcome onCandle(Candle candle, Instant now) {
        Instant start = candle.timestamp();
        if (start.plus(TRAIL_CANDLE).isAfter(now)) {
            log.debug("SL_SKIP incomplete candle={} now={}", ist(start), ist(now));
            return UpdateOutcome.IGNORED_INCOMPLETE;
        }
        if (!start.isAfter(lastSeenCandle)) {
            log.debug("SL_SKIP duplicate candle={} lastSeen={}", ist(start), ist(lastSeenCandle));
            return UpdateOutcome.IGNORED_DUPLICATE;
        }
        Instant expected = lastSeenCandle.plus(TRAIL_CANDLE);
        if (start.isAfter(expected)) {
            log.warn("SL_GAP expectedCandle={} receivedCandle={} — recover missing candles before trailing",
                    ist(expected), ist(start));
            return UpdateOutcome.MISSING_CANDLES;
        }

        blockHigh = Double.isNaN(blockHigh) ? candle.high().doubleValue() : Math.max(blockHigh, candle.high().doubleValue());
        blockLow  = Double.isNaN(blockLow)  ? candle.low().doubleValue()  : Math.min(blockLow,  candle.low().doubleValue());
        lastSeenCandle = start;
        double previous = currentStop;
        if (!armed) arm(candle);
        if (!endsBlock(start)) return currentStop == previous ? UpdateOutcome.ACCUMULATED : UpdateOutcome.UPDATED;

        double high = blockHigh, low = blockLow;
        if (armed) currentStop = direction == Direction.BULLISH ? Math.max(currentStop, low) : Math.min(currentStop, high);
        lastProcessedCandle = start;
        blockHigh = blockLow = Double.NaN;

        log.info("SL_UPDATE ts={} direction={} trailMinutes={} prevSl={} blockHigh={} blockLow={} newSl={}",
                ist(start), direction, trailMinutes, previous, high, low, currentStop);
        return currentStop == previous ? UpdateOutcome.UNCHANGED : UpdateOutcome.UPDATED;
    }

    private void arm(Candle candle) {
        double favourable = direction == Direction.BULLISH
                ? candle.high().doubleValue() - entryPrice
                : entryPrice - candle.low().doubleValue();
        bestFavourable = Math.max(bestFavourable, favourable);
        if (bestFavourable < armAfterPoints) return;
        armed = true;
        if (breakevenOnArm) {
            currentStop = direction == Direction.BULLISH
                    ? Math.max(currentStop, entryPrice + breakevenLock)
                    : Math.min(currentStop, entryPrice - breakevenLock);
        }
        log.info("SL_ARMED ts={} direction={} entry={} favourable={} stop={}",
                ist(candle.timestamp()), direction, entryPrice, bestFavourable, currentStop);
    }

    /**
     * Backtest only: accept that candles up to {@code lastCandle} are permanently missing from
     * historical data. Live code must recover the candles instead.
     */
    public void resyncTo(Instant lastCandle) {
        log.warn("SL_RESYNC from={} to={} (historical data gap)", ist(lastSeenCandle), ist(lastCandle));
        lastSeenCandle = lastCandle;
    }

    /** True if the 1-min candle starting at {@code start} is the last minute of its 09:15-aligned block. */
    private boolean endsBlock(Instant start) {
        java.time.LocalTime t = start.atZone(IST).toLocalTime();
        int sinceOpen = t.getHour() * 60 + t.getMinute() - SESSION_OPEN_MINUTE;
        return Math.floorMod(sinceOpen, trailMinutes) == trailMinutes - 1;
    }

    public boolean isHit(double spot) {
        return direction == Direction.BULLISH ? spot <= currentStop : spot >= currentStop;
    }

    /** True if price traded through the stop at any point during the candle. */
    public boolean isHitWithin(Candle candle) {
        return direction == Direction.BULLISH
                ? candle.low().doubleValue() <= currentStop
                : candle.high().doubleValue() >= currentStop;
    }

    /** Fill estimate for a stop hit inside a candle: the stop, or the open if it gapped through. */
    public double fillPrice(Candle candle) {
        double open = candle.open().doubleValue();
        return direction == Direction.BULLISH ? Math.min(open, currentStop) : Math.max(open, currentStop);
    }

    public Direction direction()          { return direction; }
    public double currentStop()           { return currentStop; }
    public Instant lastProcessedCandle()  { return lastProcessedCandle; }
    public int trailMinutes()             { return trailMinutes; }

    private static Object ist(Instant ts) { return ts == null ? null : ts.atZone(IST).toLocalDateTime(); }
}
