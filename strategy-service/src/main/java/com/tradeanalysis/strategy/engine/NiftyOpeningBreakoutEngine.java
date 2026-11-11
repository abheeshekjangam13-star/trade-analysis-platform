package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.dto.Signal;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Nifty 50 Opening Range Breakout on the first 5-minute candle (09:15–09:20 IST).
 *
 * Rules encoded:
 * - Reference candle: 09:15 IST 5-min bar → record refHigh, refLow
 * - Bullish entry:  close > refHigh (fresh cross, prev close ≤ refHigh) → buy CE at ATM−50
 * - Bearish entry:  close < refLow  (fresh cross, prev close ≥ refLow)  → buy PE at ATM+50
 * - ATM = round(spot / 50) × 50
 * - Initial SL (both directions) = reference-candle midpoint (refHigh + refLow) / 2, set when the order is placed
 * - Trailing SL after each completed block of {@code trailMinutes} 1-min candles (aligned to 09:15):
 *   CE sl = max(sl, block low), PE sl = min(sl, block high) (see {@link TrailingStopLoss});
 *   the breakout candle itself never feeds the stop
 * - SL hit: CE when spot trades ≤ sl, PE when spot trades ≥ sl, checked against each 1-min candle's
 *   low/high as a stand-in for ticks; filled at the stop, or at the open if the candle gapped through.
 *   If 1-min data is missing for a 5-min window, the hit is checked on the 5-min candle and the stop is not trailed
 * - Optional target: exit when spot moves {@code targetPoints} in favour (SL is checked first within a candle)
 * - Optional gap filter on the 09:15 open vs the previous session's close
 * - Candles still forming at {@code now} are ignored
 * - EOD square-off at 15:20 IST regardless
 * - Max {@code maxTradesPerDay} trades per day, max 1 open position at a time
 * - Second entry only on fresh breakout after position is closed
 *
 * Parameters (defaults reproduce the original rules):
 *   lots (2), directions BOTH|CE|PE (BOTH), maxTradesPerDay (2), trailMinutes (1),
 *   targetPoints (0 = none), gapFilter NONE|GAP_UP|GAP_DOWN|ANY_GAP|FLAT (NONE), gap threshold as gapPct (0.2) in %
 *   of the previous close, or gapPoints in NIFTY points (set one, not both),
 *   costPerTrade in ₹ per round trip for the whole quantity (0),
 *   entryFrom HH:mm IST (unset = no limit): only 5-min candles starting at or after this time may open a
 *   trade; the breakout must still be fresh (previous close ≤ refHigh) on that candle,
 *   entryUntil HH:mm IST (unset = no limit): only 5-min candles starting before this time may open a trade,
 *   maxGapPct (0 = off): skip the day when |gap| ≥ this many %, or maxGapPoints: ≥ this many points (one, not both),
 *   trailAfterPoints (0 = trail from the start): keep the stop at the reference midpoint until spot has
 *   moved this many points in favour of the entry, then trail as usual,
 *   breakevenOnTrail (false): when that happens, also lift the stop to the entry price,
 *   breakevenLockPoints (0): with breakevenOnTrail, put that stop this many points beyond entry in the trade's
 *   favour (CE entry + x, PE entry − x) so a trade that comes back still closes in profit,
 *   failedBreakoutPoints (0 = off; needs trailAfterPoints): before the stop starts trailing, exit at the close of a 5-min candle
 *   that closes back inside the reference range by more than this many points (CE: close < refHigh − x,
 *   PE: close > refLow + x),
 *   maxStopPoints (0 = off): initial stop is the closer of the reference midpoint and entry ∓ this many points,
 *   referenceTime (09:15): start of the 5-min reference candle (e.g. 10:45); breakouts are looked for after it,
 *   initialStop (MIDPOINT): MIDPOINT of the reference candle, or OPPOSITE side (CE → reference low, PE → reference high),
 *   onlyOn (none): yyyy-MM-dd — trade on this date only,
 *   skipDays (none): comma-separated weekdays not to trade, e.g. "THU" or "MON,FRIDAY" (case-insensitive;
 *   3-letter or full names). A skipped day still supplies the previous close for the next day's gap,
 *   referenceMinutes (5): length of the reference range from referenceTime, a multiple of 5 up to 60 (15 = the
 *   first 15-min candle, 09:15–09:30, built from the three 5-min candles); breakouts are looked for after it,
 *   directions WITH_GAP: CE only on a gap-up open, PE only on a gap-down open (pair with gapFilter ANY_GAP),
 *   initialStop BREAKOUT_CANDLE: CE → low of the 5-min breakout candle, PE → its high,
 *   targetR (0 = off; not with targetPoints): target at this multiple of the initial risk |entry − initial stop|,
 *   trailAfterR (0 = off; not with trailAfterPoints): like trailAfterPoints, with the trigger at this multiple of
 *   the initial risk,
 *   breakoutStrength (0 = off, 0–1): the breakout candle must close in this top fraction of its range for CE
 *   ((close − low) / (high − low) ≥ x), or bottom fraction for PE; a weaker breakout is skipped and the next entry
 *   needs a fresh cross,
 *   trendSma (0 = off): CE only when the previous session's close is above the simple average of the last N session
 *   closes, PE only when below (needs N sessions of 5-min history before the day; see {@link #warmupDays})
 */
@Component
public class NiftyOpeningBreakoutEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime REF_CANDLE_TIME  = LocalTime.of(9, 15);
    private static final LocalTime EOD_EXIT_TIME    = LocalTime.of(15, 20);

    public enum GapFilter { NONE, GAP_UP, GAP_DOWN, ANY_GAP, FLAT }

    /** openTrades: trades still open at {@code now} (live mode only; a backtest squares them off at the end of data). */
    /** Throws IllegalArgumentException with a readable message if the parameters are not valid for this engine. */
    public static void validateParams(Params params) { Settings.from(params); }

    /** Calendar days of 5-min history to load before the first trading day: the previous close, plus the trend average. */
    public static int warmupDays(Params params) {
        int n = Math.max(0, params.intOr("trendSma", 0));
        if (n == 0) return 7;   // only the previous close is needed
        return Math.max(7, (int) Math.ceil(n * 7 / 5.0) + 10);   // N sessions ≈ 7N/5 calendar days, plus holidays
    }

    /** The underlying a strategy trades, from its parameters (NIFTY unless set to BANKNIFTY). */
    public static com.tradeanalysis.common.market.Underlying underlyingOf(Params params) {
        return com.tradeanalysis.common.market.Underlying.of(params.stringOr("underlying", ""));
    }

    public record BreakoutResult(List<Signal> signals, List<NiftyTradeLog> tradeLogs, List<DayReport> days,
                                 List<NiftyTradeLog> openTrades) {
        public BreakoutResult(List<Signal> signals, List<NiftyTradeLog> tradeLogs) { this(signals, tradeLogs, List.of(), List.of()); }
        public BreakoutResult(List<Signal> signals, List<NiftyTradeLog> tradeLogs, List<DayReport> days) {
            this(signals, tradeLogs, days, List.of());
        }
    }

    /** What happened on one trading day from {@code tradeFrom}: TRADED, NO_TRADE, SKIPPED or NO_DATA, and why. */
    public record DayReport(LocalDate date, Double gapPercent, String status, String reason, int trades) {}

    private record Exit(Candle bar, double price, String reason) {}

    enum StopBasis { MIDPOINT, OPPOSITE, BREAKOUT_CANDLE }

    private record Settings(int lots, int lotSize, boolean allowCe, boolean allowPe, int maxTradesPerDay,
                            int trailMinutes, double targetPoints, GapFilter gapFilter, Threshold gap,
                            double costPerTrade, LocalTime entryFrom, LocalTime entryUntil, Threshold maxGap,
                            double trailAfterPoints, boolean breakevenOnTrail,
                            double failedBreakoutPoints, double maxStopPoints, double breakevenLockPoints,
                            Set<DayOfWeek> skipDays, LocalTime referenceTime, StopBasis stopBasis, LocalDate onlyOn,
                            com.tradeanalysis.common.market.Underlying underlying, int referenceMinutes,
                            boolean withGap, double targetR, double trailAfterR, double breakoutStrength, int trendSma) {
        static Settings from(Params p) {
            com.tradeanalysis.common.market.Underlying u = com.tradeanalysis.common.market.Underlying.of(p.stringOr("underlying", ""));
            String dirs = p.stringOr("directions", "BOTH").toUpperCase();
            if (!Set.of("BOTH", "CE", "PE", "WITH_GAP").contains(dirs))
                throw new IllegalArgumentException("directions must be BOTH, CE, PE or WITH_GAP: " + dirs);
            int refMinutes = p.intOr("referenceMinutes", 5);
            if (refMinutes < 5 || refMinutes > 60 || refMinutes % 5 != 0)
                throw new IllegalArgumentException("referenceMinutes must be a multiple of 5 from 5 to 60: " + refMinutes);
            double targetR = p.doubleOr("targetR", 0), trailAfterR = p.doubleOr("trailAfterR", 0);
            if (targetR < 0 || trailAfterR < 0)
                throw new IllegalArgumentException("targetR and trailAfterR can't be negative");
            if (targetR > 0 && p.doubleOr("targetPoints", 0) > 0)
                throw new IllegalArgumentException("Set targetR or targetPoints, not both");
            if (trailAfterR > 0 && p.doubleOr("trailAfterPoints", 0) > 0)
                throw new IllegalArgumentException("Set trailAfterR or trailAfterPoints, not both");
            double strength = p.doubleOr("breakoutStrength", 0);
            if (strength < 0 || strength > 1)
                throw new IllegalArgumentException("breakoutStrength must be between 0 and 1: " + strength);
            int trendSma = p.intOr("trendSma", 0);
            if (trendSma < 0 || trendSma > 200)
                throw new IllegalArgumentException("trendSma must be from 0 to 200 sessions: " + trendSma);
            // The lot size is the index's own (NIFTY 65, Bank Nifty 30), not a parameter: quantity = lots × lot size.
            return new Settings(p.intOr("lots", 2), u.defaultLotSize,
                    !dirs.equals("PE"), !dirs.equals("CE"),
                    p.intOr("maxTradesPerDay", 2), p.intOr("trailMinutes", 1),
                    p.doubleOr("targetPoints", 0), GapFilter.valueOf(p.stringOr("gapFilter", "NONE").toUpperCase()),
                    Threshold.from(p, "gapPct", "gapPoints", 0.2), p.doubleOr("costPerTrade", 0),
                    parseTime("entryFrom", p.stringOr("entryFrom", "")),
                    parseTime("entryUntil", p.stringOr("entryUntil", "")),
                    Threshold.from(p, "maxGapPct", "maxGapPoints", 0), p.doubleOr("trailAfterPoints", 0),
                    Boolean.parseBoolean(p.stringOr("breakevenOnTrail", "false").trim()),
                    p.doubleOr("failedBreakoutPoints", 0), p.doubleOr("maxStopPoints", 0),
                    p.doubleOr("breakevenLockPoints", 0),
                    parseDays(p.stringOr("skipDays", "")),
                    Optional.ofNullable(parseTime("referenceTime", p.stringOr("referenceTime", ""))).orElse(REF_CANDLE_TIME),
                    parseStop(p.stringOr("initialStop", "MIDPOINT")),
                    parseDate("onlyOn", p.stringOr("onlyOn", "")), u, refMinutes,
                    dirs.equals("WITH_GAP"), targetR, trailAfterR, strength, trendSma);
        }

        private static StopBasis parseStop(String v) {
            return switch (v.trim().toUpperCase()) {
                case "MIDPOINT", "" -> StopBasis.MIDPOINT;
                case "OPPOSITE" -> StopBasis.OPPOSITE;
                case "BREAKOUT_CANDLE" -> StopBasis.BREAKOUT_CANDLE;
                default -> throw new IllegalArgumentException("initialStop must be MIDPOINT, OPPOSITE or BREAKOUT_CANDLE: " + v);
            };
        }

        private static LocalDate parseDate(String name, String v) {
            if (v == null || v.isBlank()) return null;
            try { return LocalDate.parse(v.trim()); }
            catch (DateTimeException e) { throw new IllegalArgumentException(name + " must be yyyy-MM-dd: " + v); }
        }

        private static Set<DayOfWeek> parseDays(String v) {
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            if (v == null || v.isBlank()) return days;
            for (String raw : v.split(",")) {
                String d = raw.trim().toUpperCase();
                if (d.isEmpty()) continue;
                DayOfWeek match = Arrays.stream(DayOfWeek.values())
                        .filter(w -> w.name().equals(d) || (d.length() == 3 && w.name().startsWith(d)))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "skipDays must be weekdays like MON,TUE or MONDAY: " + raw.trim()));
                days.add(match);
            }
            return days;
        }

        private static LocalTime parseTime(String name, String v) {
            if (v == null || v.isBlank()) return null;
            try { return LocalTime.parse(v.trim()); }
            catch (DateTimeException e) { throw new IllegalArgumentException(name + " must be HH:mm: " + v); }
        }

        boolean entryWindowOpen(LocalTime t) {
            return (entryFrom == null || !t.isBefore(entryFrom)) && (entryUntil == null || t.isBefore(entryUntil));
        }

        /**
         * Why the gap rules keep this day from trading, or null if it may trade. {@code gapPct} is the 09:15 open vs
         * the previous close in %, {@code gapPoints} the same in points; each rule uses the unit it was set in.
         */
        String dayBlockReason(Double gapPct, Double gapPoints) {
            Double big = maxGap.of(gapPct, gapPoints);
            if (maxGap.value() > 0 && big != null && Math.abs(big) >= maxGap.value())
                return String.format("Gap %s beyond max gap %s", maxGap.show(big), maxGap.show(maxGap.value()));
            if (gapFilter == GapFilter.NONE) return null;
            Double g = gap.of(gapPct, gapPoints);
            if (g == null) return "No previous close to measure the gap";
            double t = gap.value();
            boolean ok = switch (gapFilter) {
                case GAP_UP   -> g >= t;
                case GAP_DOWN -> g <= -t;
                case ANY_GAP  -> Math.abs(g) >= t;
                case FLAT     -> Math.abs(g) < t;
                case NONE     -> true;
            };
            return ok ? null : String.format("Gap %s does not meet %s %s", gap.show(g), gapFilter, gap.show(t));
        }
    }

    /** A gap threshold in % of the previous close or in NIFTY points, from whichever of the two keys is set. */
    record Threshold(double value, boolean points) {
        static Threshold from(Params p, String pctKey, String pointsKey, double defaultPct) {
            boolean hasPct = !p.stringOr(pctKey, "").isBlank();
            boolean hasPts = !p.stringOr(pointsKey, "").isBlank();
            if (hasPct && hasPts)
                throw new IllegalArgumentException("Set " + pctKey + " or " + pointsKey + ", not both");
            double v = hasPts ? p.doubleOr(pointsKey, 0) : p.doubleOr(pctKey, defaultPct);
            if (v < 0) throw new IllegalArgumentException((hasPts ? pointsKey : pctKey) + " can't be negative: " + v);
            return new Threshold(v, hasPts);
        }
        Double of(Double pct, Double pts) { return points ? pts : pct; }
        String show(double v) { return points ? String.format("%.2f pts", v) : String.format("%.2f%%", v); }
    }

    /**
     * @param series   5-minute candles (reference candle, entries, EOD)
     * @param minutes  1-minute candles over the same period (stop-loss hit and trailing)
     */
    public BreakoutResult run(PriceSeries series, PriceSeries minutes, Params params, Long strategyId) {
        return run(series, minutes, params, strategyId, Instant.now(), null);
    }

    public BreakoutResult run(PriceSeries series, PriceSeries minutes, Params params, Long strategyId, Instant now) {
        return run(series, minutes, params, strategyId, now, null);
    }

    /**
     * @param tradeFrom first date to trade (inclusive); earlier days in {@code series} only supply the
     *                  previous close for the gap filter. Null trades every day.
     */
    public BreakoutResult run(PriceSeries series, PriceSeries minutes, Params params, Long strategyId,
                              Instant now, LocalDate tradeFrom) {
        return run(series, minutes, params, strategyId, now, tradeFrom, true);
    }

    /** Live use: the same rules, but a trade still open at {@code now} stays open (in openTrades, with its current stop). */
    public BreakoutResult runLive(PriceSeries series, PriceSeries minutes, Params params, Long strategyId,
                                  Instant now, LocalDate tradeFrom) {
        // referenceMode=PREVIOUS_DAY: yesterday's high and low are the reference, not today's 09:15 candle.
        if (PreviousRangeEngine.handles(params)) return new PreviousRangeEngine().runLive(series, minutes, params, strategyId, now, tradeFrom);
        // referenceMode=GAP_FILL: trade back into the opening gap.
        if (GapFillEngine.handles(params)) return new GapFillEngine().runLive(series, minutes, params, strategyId, now, tradeFrom);
        return run(series, minutes, params, strategyId, now, tradeFrom, false);
    }

    private BreakoutResult run(PriceSeries series, PriceSeries minutes, Params params, Long strategyId,
                               Instant now, LocalDate tradeFrom, boolean closeAtEnd) {
        Settings cfg = Settings.from(params);

        // Group and sort candles by IST trading date
        Map<LocalDate, List<Candle>> byDay = series.candles().stream()
                .collect(Collectors.groupingBy(
                        c -> c.timestamp().atZone(IST).toLocalDate(),
                        TreeMap::new,
                        Collectors.toList()));

        NavigableMap<Instant, Candle> oneMin = new TreeMap<>();
        minutes.candles().forEach(c -> oneMin.put(c.timestamp(), c));

        List<Signal> signals  = new ArrayList<>();
        List<NiftyTradeLog> logs = new ArrayList<>();
        List<DayReport> days = new ArrayList<>();
        List<NiftyTradeLog> openTrades = new ArrayList<>();
        Double prevSessionClose = null;
        List<Double> sessionCloses = new ArrayList<>();   // every earlier session's close, for trendSma

        for (Map.Entry<LocalDate, List<Candle>> entry : byDay.entrySet()) {
            LocalDate date = entry.getKey();
            List<Candle> dayCandles = entry.getValue().stream()
                    .sorted(Comparator.comparing(Candle::timestamp))
                    .toList();
            Double prevClose0 = prevSessionClose;
            prevSessionClose = dayCandles.get(dayCandles.size() - 1).close().doubleValue();
            // Average of the last trendSma closes before this day (null when off or not enough history)
            Double trendAvg = cfg.trendSma() > 0 && sessionCloses.size() >= cfg.trendSma()
                    ? sessionCloses.subList(sessionCloses.size() - cfg.trendSma(), sessionCloses.size()).stream()
                            .mapToDouble(Double::doubleValue).average().orElseThrow()
                    : null;
            sessionCloses.add(prevSessionClose);
            if (tradeFrom != null && date.isBefore(tradeFrom)) continue;

            // The reference candle (09:15 unless referenceTime says otherwise); the gap is always the 09:15 open's.
            Candle refCandle = dayCandles.stream()
                    .filter(c -> c.timestamp().atZone(IST).toLocalTime().equals(cfg.referenceTime()))
                    .findFirst().orElse(null);
            Candle openCandle = dayCandles.stream()
                    .filter(c -> c.timestamp().atZone(IST).toLocalTime().equals(REF_CANDLE_TIME))
                    .findFirst().orElse(refCandle);
            Double gapPoints = openCandle == null || prevClose0 == null ? null : openCandle.open().doubleValue() - prevClose0;
            Double gap = gapPoints == null ? null : gapPoints / prevClose0 * 100;

            if (cfg.onlyOn() != null && !cfg.onlyOn().equals(date)) {
                days.add(new DayReport(date, gap, "SKIPPED", "Strategy trades only on " + cfg.onlyOn(), 0));
                continue;
            }

            if (cfg.skipDays().contains(date.getDayOfWeek())) {   // its close still feeds the next gap
                days.add(new DayReport(date, gap, "SKIPPED", "skipDays includes " + date.getDayOfWeek(), 0));
                continue;
            }
            if (refCandle == null) {
                days.add(new DayReport(date, null, "NO_DATA", "No " + cfg.referenceTime() + " reference candle", 0));
                continue;
            }
            if (cfg.trendSma() > 0 && trendAvg == null) {
                days.add(new DayReport(date, gap, "NO_DATA", "Fewer than " + cfg.trendSma() + " earlier sessions for the trend average", 0));
                continue;
            }
            String blocked = cfg.dayBlockReason(gap, gapPoints);
            if (blocked != null) {
                days.add(new DayReport(date, gap, "NO_TRADE", blocked, 0));
                continue;
            }

            // The reference range: the 5-min candles from referenceTime spanning referenceMinutes (one candle by default).
            Instant refEnd = refCandle.timestamp().plus(Duration.ofMinutes(cfg.referenceMinutes()));
            List<Candle> refCandles = dayCandles.stream()
                    .filter(c -> !c.timestamp().isBefore(refCandle.timestamp()) && c.timestamp().isBefore(refEnd))
                    .toList();
            if (refCandles.size() < cfg.referenceMinutes() / 5) {
                days.add(new DayReport(date, gap, "NO_DATA", "Incomplete " + cfg.referenceMinutes() + "-min reference range from "
                        + cfg.referenceTime(), 0));
                continue;
            }
            Candle lastRef = refCandles.get(refCandles.size() - 1);
            double refHigh = refCandles.stream().mapToDouble(c -> c.high().doubleValue()).max().orElseThrow();
            double refLow  = refCandles.stream().mapToDouble(c -> c.low().doubleValue()).min().orElseThrow();
            // WITH_GAP: trade only in the direction of the open's gap.
            boolean ceToday = cfg.allowCe() && (!cfg.withGap() || (gapPoints != null && gapPoints > 0));
            boolean peToday = cfg.allowPe() && (!cfg.withGap() || (gapPoints != null && gapPoints < 0));
            if (trendAvg != null) {   // trade only with the trend: CE above the average, PE below it
                ceToday &= prevClose0 != null && prevClose0 > trendAvg;
                peToday &= prevClose0 != null && prevClose0 < trendAvg;
                if (!ceToday && !peToday) {
                    days.add(new DayReport(date, gap, "NO_TRADE", String.format("Previous close %.2f is on the wrong side of the %d-day average %.2f",
                            prevClose0, cfg.trendSma(), trendAvg), 0));
                    continue;
                }
            }

            List<Candle> tradingCandles = dayCandles.stream()
                    .filter(c -> c.timestamp().isAfter(lastRef.timestamp()))
                    .toList();

            int tradesThisDay  = 0;
            NiftyTradeLog open = null;
            TrailingStopLoss stop = null;
            double openTarget  = 0;   // target distance in points for the open trade (0 = none)
            double prevClose   = lastRef.close().doubleValue();

            for (Candle candle : tradingCandles) {
                if (candle.timestamp().plus(TrailingStopLoss.ENTRY_CANDLE).isAfter(now)) break; // still forming
                LocalTime ist = candle.timestamp().atZone(IST).toLocalTime();
                double spot   = candle.close().doubleValue();

                // ── EOD square-off ──────────────────────────────────────────
                if (!ist.isBefore(EOD_EXIT_TIME)) {
                    if (open != null) {
                        open.close(candle.timestamp(), candle.open().doubleValue(), "EOD_SQUAREOFF", stop.currentStop());
                        logs.add(open);
                        signals.add(exitSignal(candle, open, "EOD square-off 15:20 IST"));
                        open = null;
                    }
                    break; // no more entries after EOD
                }

                // ── SL / target: walk the 1-min candles inside this 5-min candle ─
                if (open != null) {
                    Exit exit = checkExit(stop, open, openTarget, candle, oneMin, now);
                    if (exit != null) {
                        open.close(exit.bar().timestamp(), exit.price(), exit.reason(), stop.currentStop());
                        logs.add(open);
                        String why = exit.reason().equals("TARGET_HIT")
                                ? String.format("Target hit @ %.2f", exit.price())
                                : String.format("SL hit @ %.2f", stop.currentStop());
                        signals.add(exitSignal(exit.bar(), open, why));
                        open = null;
                        stop = null;
                    } else if (failedBreakout(cfg, stop, open, candle)) {
                        double px = candle.close().doubleValue();
                        open.close(candle.timestamp(), px, "FAILED_BREAKOUT", stop.currentStop());
                        logs.add(open);
                        signals.add(exitSignal(candle, open, String.format("Failed breakout: close %.2f back inside reference range", px)));
                        open = null;
                        stop = null;
                    }
                }

                // ── Entry check ─────────────────────────────────────────────
                if (open == null && tradesThisDay < cfg.maxTradesPerDay() && cfg.entryWindowOpen(ist)) {
                    boolean freshBullish = ceToday && prevClose <= refHigh && spot > refHigh;
                    boolean freshBearish = peToday && prevClose >= refLow  && spot < refLow;
                    if ((freshBullish || freshBearish) && cfg.breakoutStrength() > 0) {
                        double range = candle.high().doubleValue() - candle.low().doubleValue();
                        double pos = range > 0 ? (spot - candle.low().doubleValue()) / range : 1.0;
                        if ((freshBullish ? pos : 1 - pos) < cfg.breakoutStrength()) freshBullish = freshBearish = false;
                    }

                    if (freshBullish || freshBearish) {
                        TrailingStopLoss.Direction dir = freshBullish
                                ? TrailingStopLoss.Direction.BULLISH : TrailingStopLoss.Direction.BEARISH;
                        String optType = freshBullish ? "CE" : "PE";
                        int strike = cfg.underlying().strike(freshBullish, spot);
                        stop = switch (cfg.stopBasis()) {
                            case OPPOSITE -> TrailingStopLoss.openAt(dir, freshBullish ? refLow : refHigh, candle.timestamp(), cfg.trailMinutes());
                            case BREAKOUT_CANDLE -> TrailingStopLoss.openAt(dir,
                                    (freshBullish ? candle.low() : candle.high()).doubleValue(), candle.timestamp(), cfg.trailMinutes());
                            case MIDPOINT -> TrailingStopLoss.open(dir, refHigh, refLow, candle.timestamp(), cfg.trailMinutes());
                        };
                        if (cfg.maxStopPoints() > 0)
                            stop.tightenTo(freshBullish ? spot - cfg.maxStopPoints() : spot + cfg.maxStopPoints());
                        // Initial risk in points; floored at 1 so a breakout candle closing on its extreme still gets a target.
                        double risk = Math.max(1, Math.abs(spot - stop.currentStop()));
                        openTarget = cfg.targetR() > 0 ? cfg.targetR() * risk : cfg.targetPoints();
                        double trailAfter = cfg.trailAfterR() > 0 ? cfg.trailAfterR() * risk : cfg.trailAfterPoints();
                        if (trailAfter > 0) stop.trailOnlyAfter(spot, trailAfter, cfg.breakevenOnTrail(),
                                cfg.breakevenLockPoints());
                        open = new NiftyTradeLog(strategyId, series.symbol(), date,
                                ++tradesThisDay, dir.name(), refHigh, refLow,
                                refCandle.timestamp(), candle.timestamp(), spot,
                                optType, strike, cfg.lots(), cfg.lotSize(), stop.currentStop());
                        open.setContext(gap, cfg.costPerTrade());
                        signals.add(entrySignal(candle, optType, strike, cfg.underlying().atm(spot), refHigh, refLow, stop.currentStop(), gap));
                    }
                }

                prevClose = spot;
            }

            days.add(tradesThisDay > 0
                    ? new DayReport(date, gap, "TRADED", null, tradesThisDay)
                    : new DayReport(date, gap, "NO_TRADE", "No fresh breakout in the entry window", 0));

            // Residual open position that didn't hit EOD candle (data ends before 15:20)
            if (open != null && !closeAtEnd) {
                open.trackStop(stop.currentStop());
                openTrades.add(open);
                open = null;
            }
            if (open != null) {
                Candle last = tradingCandles.stream()
                        .filter(c -> !c.timestamp().plus(TrailingStopLoss.ENTRY_CANDLE).isAfter(now))
                        .reduce((a, b) -> b).orElseThrow();
                open.close(last.timestamp(), last.close().doubleValue(), "EOD_SQUAREOFF", stop.currentStop());
                logs.add(open);
                signals.add(exitSignal(last, open, "EOD square-off (end of data)"));
            }
        }

        return new BreakoutResult(signals, logs, days, openTrades);
    }

    /**
     * Checks the stop (then the target) and trails the stop on each 1-min candle of the given 5-min
     * candle, in order. Returns the exit, or null if the position stays open.
     */
    private Exit checkExit(TrailingStopLoss stop, NiftyTradeLog trade, double targetPoints, Candle fiveMin,
                           NavigableMap<Instant, Candle> oneMin, Instant now) {
        Collection<Candle> bars = oneMin.subMap(fiveMin.timestamp(), true,
                fiveMin.timestamp().plus(TrailingStopLoss.ENTRY_CANDLE), false).values();
        if (bars.isEmpty()) {
            // No 1-min data for this window: fall back to the 5-min range, keep the stop where it is.
            if (stop.isHitWithin(fiveMin)) return new Exit(fiveMin, stop.fillPrice(fiveMin), "SL_HIT");
            return targetExit(trade, targetPoints, fiveMin);
        }
        for (Candle bar : bars) {
            if (stop.isHitWithin(bar)) return new Exit(bar, stop.fillPrice(bar), "SL_HIT");
            Exit target = targetExit(trade, targetPoints, bar);
            if (target != null) return target;
            if (stop.onCandle(bar, now) == TrailingStopLoss.UpdateOutcome.MISSING_CANDLES) {
                // Historical 1-min data has a hole that cannot be recovered; resume from this bar.
                stop.resyncTo(bar.timestamp().minus(TrailingStopLoss.TRAIL_CANDLE));
                stop.onCandle(bar, now);
            }
        }
        return null;
    }

    /** Completed 5-min candle closed back inside the reference range before the stop started trailing. */
    private static boolean failedBreakout(Settings cfg, TrailingStopLoss stop, NiftyTradeLog trade, Candle candle) {
        if (cfg.failedBreakoutPoints() <= 0 || stop.isTrailing()) return false;
        double close = candle.close().doubleValue();
        return trade.getOptionType().equals("CE")
                ? close < trade.getReferenceHigh() - cfg.failedBreakoutPoints()
                : close > trade.getReferenceLow() + cfg.failedBreakoutPoints();
    }

    private Exit targetExit(NiftyTradeLog trade, double targetPoints, Candle bar) {
        if (targetPoints <= 0) return null;
        double open = bar.open().doubleValue();
        if (trade.getOptionType().equals("CE")) {
            double target = trade.getEntrySpotPrice() + targetPoints;
            return bar.high().doubleValue() >= target ? new Exit(bar, Math.max(open, target), "TARGET_HIT") : null;
        }
        double target = trade.getEntrySpotPrice() - targetPoints;
        return bar.low().doubleValue() <= target ? new Exit(bar, Math.min(open, target), "TARGET_HIT") : null;
    }

    private Signal entrySignal(Candle c, String optType, int strike, int atm, double refH, double refL,
                               double sl, Double gap) {
        String reason = String.format("ENTRY %s | ATM %d | Strike %d | RefHigh %.2f | RefLow %.2f | InitialSL %.2f | Gap %s",
                optType, atm, strike, refH, refL, sl, gap == null ? "n/a" : String.format("%.2f%%", gap));
        return new Signal(c.timestamp(), Signal.SignalType.BUY,
                BigDecimal.valueOf(c.close().doubleValue()), reason);
    }

    private Signal exitSignal(Candle c, NiftyTradeLog trade, String reason) {
        String msg = String.format("EXIT %s %d | %s | EntrySpot %.2f | ExitSpot %.2f | InitialSL %.2f | FinalSL %.2f | EstPnL %.2f",
                trade.getOptionType(), trade.getStrikePrice(), reason,
                trade.getEntrySpotPrice(), trade.getExitSpotPrice(),
                trade.getInitialStopLoss(), trade.getFinalStopLoss(), trade.getEstimatedPnl());
        return new Signal(c.timestamp(), Signal.SignalType.SELL,
                BigDecimal.valueOf(c.close().doubleValue()), msg);
    }
}
