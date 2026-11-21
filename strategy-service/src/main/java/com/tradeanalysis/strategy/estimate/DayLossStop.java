package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.model.NiftyTradeLog;

import java.time.*;
import java.util.*;

/**
 * The user's max day loss in a backtest, applied the way live trading applies it (LiveStrategyRunner#enforceDayLoss).
 * After every minute, the day's P&L of all the trades together is checked: trades already closed, plus open ones
 * valued at that minute's index close. Once it is at or below −limit, every open trade is closed at that close (exit
 * reason {@link #REASON}) and the day's later entries are not taken (skip reason {@link #REASON}).
 *
 * <p>With {@code atLimit} (live trading checks every couple of seconds, see LiveExitWatcher), the stop is taken where
 * the limit was reached inside the minute instead of at the minute's close ({@link #stopDayAtLimit}): the open trades
 * close at the point where the day's P&L is −limit, also ones whose own stop lay beyond it, so a stop bigger than the
 * limit no longer goes past it; only a jump inside a single price step (a gap) can.
 *
 * <p>Values are the trades' own: the option's real price at that minute for trades priced by OptionPrices, else the
 * estimate ({@link NiftyTradeLog#pnlAt}: index move × lot size × lots × 0.5), minus charges. Give it the trades that were taken (after the capital fit), with their final lots.
 */
public final class DayLossStop {

    public static final String REASON = "MAX_DAY_LOSS";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** One day the limit was reached: when, the day's P&L at that moment, and how many trades it closed / kept out. */
    public record Stop(LocalDate date, Instant at, double pnlAtStop, int closed, int blocked) {
        /** Shown on the day: e.g. "Max day loss ₹5,000 reached at 11:42 (−₹5,120): 2 open trades closed, 1 entry not taken". */
        public String describe(double limit) {
            return String.format(Locale.ROOT, "Max day loss %s reached at %s (%s): %d open trade%s closed, %d later entr%s not taken",
                    rupees(limit), LocalTime.ofInstant(at, IST).withSecond(0).withNano(0), signedRupees(pnlAtStop),
                    closed, closed == 1 ? "" : "s", blocked, blocked == 1 ? "y" : "ies");
        }
    }

    /** The trades still taken (some closed early by the stop) and the trades it kept out (marked skipped). */
    public record Result(List<NiftyTradeLog> kept, List<NiftyTradeLog> blocked, List<Stop> stops) {
        public static Result none(List<NiftyTradeLog> trades) { return new Result(trades, List.of(), List.of()); }
        public Map<LocalDate, Stop> byDate() {
            Map<LocalDate, Stop> m = new LinkedHashMap<>();
            stops.forEach(s -> m.put(s.date(), s));
            return m;
        }
    }

    private DayLossStop() {}

    /**
     * @param limit   the max day loss in rupees; null or ≤ 0 = none, the trades come back untouched
     * @param minutes each index's 1-minute candles (by symbol, e.g. "NIFTY 50"), covering the trades' days
     */
    public static Result apply(List<NiftyTradeLog> trades, Double limit, Map<String, PriceSeries> minutes) {
        return apply(trades, limit, minutes, false);
    }

    /** As {@link #apply(List, Double, Map)}; {@code atLimit} stops where the limit is reached inside the minute. */
    public static Result apply(List<NiftyTradeLog> trades, Double limit, Map<String, PriceSeries> minutes, boolean atLimit) {
        if (limit == null || limit <= 0 || trades.isEmpty()) return Result.none(trades);
        Map<String, NavigableMap<Instant, Double>> closes = new HashMap<>();
        Map<String, Map<Instant, double[]>> ranges = new HashMap<>();   // low, high
        minutes.forEach((sym, series) -> {
            NavigableMap<Instant, Double> m = new TreeMap<>();
            Map<Instant, double[]> r = new HashMap<>();
            if (series != null) for (Candle c : series.candles()) {
                m.put(c.timestamp(), c.close().doubleValue());
                r.put(c.timestamp(), new double[]{c.low().doubleValue(), c.high().doubleValue()});
            }
            closes.put(sym, m);
            ranges.put(sym, r);
        });

        TreeMap<LocalDate, List<NiftyTradeLog>> byDay = new TreeMap<>();
        for (NiftyTradeLog t : trades) byDay.computeIfAbsent(t.getTradeDate(), d -> new ArrayList<>()).add(t);
        List<NiftyTradeLog> kept = new ArrayList<>(), blocked = new ArrayList<>();
        List<Stop> stops = new ArrayList<>();
        for (Map.Entry<LocalDate, List<NiftyTradeLog>> day : byDay.entrySet()) {
            List<NiftyTradeLog> list = new ArrayList<>(day.getValue());
            list.sort(Comparator.comparing(NiftyTradeLog::getEntryTimestamp));
            Stop stop = atLimit ? stopDayAtLimit(day.getKey(), list, limit, closes, ranges) : stopDay(day.getKey(), list, limit, closes);
            for (NiftyTradeLog t : list) (t.getSkipReason() != null ? blocked : kept).add(t);
            if (stop != null) stops.add(stop);
        }
        kept.sort(Comparator.comparing(NiftyTradeLog::getEntryTimestamp));
        return new Result(kept, blocked, stops);
    }

    /** Checks one day minute by minute; on reaching the limit closes the open trades and marks the later ones skipped. */
    static Stop stopDay(LocalDate date, List<NiftyTradeLog> trades, double limit, Map<String, NavigableMap<Instant, Double>> closes) {
        Instant dayStart = date.atStartOfDay(IST).toInstant(), dayEnd = date.plusDays(1).atStartOfDay(IST).toInstant();
        Map<NiftyTradeLog, Instant> entered = new IdentityHashMap<>();
        for (NiftyTradeLog t : trades) entered.put(t, enteredAt(closes, t));
        Instant first = Collections.min(entered.values());
        // Every minute of the day (any traded index's candles) from the first entry on; a candle stamped t covers t..t+1m.
        TreeSet<Instant> minuteStarts = new TreeSet<>();
        for (NiftyTradeLog t : trades) {
            NavigableMap<Instant, Double> m = closes.getOrDefault(t.getSymbol(), Collections.emptyNavigableMap());
            minuteStarts.addAll(m.subMap(dayStart, true, dayEnd, false).keySet());
        }
        for (Instant start : minuteStarts) {
            Instant end = start.plusSeconds(60);
            if (!end.isAfter(first)) continue;
            double pnl = 0;
            List<NiftyTradeLog> open = new ArrayList<>();
            for (NiftyTradeLog t : trades) {
                if (entered.get(t).isAfter(end)) continue;                                // not entered yet
                if (t.getExitTimestamp() != null && !t.getExitTimestamp().isAfter(end)) pnl += t.getEstimatedPnl();
                else { open.add(t); pnl += t.pnlAt(end, spot(closes, t, start)); }   // real option price when known
            }
            if (pnl > -limit) continue;
            int closed = 0, notTaken = 0;
            for (NiftyTradeLog t : open) {
                t.close(end, spot(closes, t, start), REASON, t.getFinalStopLoss() == null ? 0 : t.getFinalStopLoss());
                closed++;
            }
            for (NiftyTradeLog t : trades)
                if (entered.get(t).isAfter(end)) { t.markSkipped(REASON); notTaken++; }
            return new Stop(date, end, pnl, closed, notTaken);
        }
        return null;
    }

    /**
     * The tick-level version of {@link #stopDay}. A trade's own exit counts once its exit minute is over (an engine
     * stamps the exit with the start of the minute it happened in). In each minute the index is taken to move in a
     * straight line from the previous close to the minute's worst price for the trades held (the low for calls, the high
     * for puts; the close when both are held). Where the day's P&L reaches −limit on that path, the trades still held
     * close there: also ones whose own stop lay further along it, which a few-second check reaches first. A stop or
     * target reached before that point keeps its own exit. Trades entering at that minute's close and later are not
     * taken. If the limit is passed only by a fill the path can't explain, the minute-close rule applies to that minute.
     */
    static Stop stopDayAtLimit(LocalDate date, List<NiftyTradeLog> trades, double limit,
                               Map<String, NavigableMap<Instant, Double>> closes, Map<String, Map<Instant, double[]>> ranges) {
        Instant dayStart = date.atStartOfDay(IST).toInstant(), dayEnd = date.plusDays(1).atStartOfDay(IST).toInstant();
        Map<NiftyTradeLog, Instant> entered = new IdentityHashMap<>();
        for (NiftyTradeLog t : trades) entered.put(t, enteredAt(closes, t));
        Instant first = Collections.min(entered.values());
        TreeSet<Instant> minuteStarts = new TreeSet<>();
        for (NiftyTradeLog t : trades) {
            NavigableMap<Instant, Double> m = closes.getOrDefault(t.getSymbol(), Collections.emptyNavigableMap());
            minuteStarts.addAll(m.subMap(dayStart, true, dayEnd, false).keySet());
        }
        for (Instant start : minuteStarts) {
            Instant end = start.plusSeconds(60);
            if (!end.isAfter(first)) continue;
            double pnl = 0;
            List<NiftyTradeLog> open = new ArrayList<>();
            for (NiftyTradeLog t : trades) {
                if (entered.get(t).isAfter(end)) continue;
                if (t.getExitTimestamp() != null && t.getExitTimestamp().isBefore(end)) pnl += t.getEstimatedPnl();   // its minute is over
                else { open.add(t); pnl += t.pnlAt(end, spot(closes, t, start)); }
            }
            if (pnl > -limit) continue;
            return stopInMinute(date, trades, entered, open, limit, closes, ranges, start, end);
        }
        return null;
    }

    private static Stop stopInMinute(LocalDate date, List<NiftyTradeLog> trades, Map<NiftyTradeLog, Instant> entered,
                                     List<NiftyTradeLog> open, double limit, Map<String, NavigableMap<Instant, Double>> closes,
                                     Map<String, Map<Instant, double[]>> ranges, Instant start, Instant end) {
        Instant before = start.minusSeconds(60);
        double fixed = 0;
        List<NiftyTradeLog> held = new ArrayList<>();
        for (NiftyTradeLog t : trades) {
            Instant in = entered.get(t);
            if (in.isAfter(start)) continue;                                                         // enters at this close or later
            if (t.getExitTimestamp() != null && t.getExitTimestamp().isBefore(start)) fixed += t.getEstimatedPnl();
            else held.add(t);
        }
        boolean calls = held.stream().allMatch(t -> "CE".equals(t.getOptionType()));
        boolean puts = held.stream().allMatch(t -> "PE".equals(t.getOptionType()));
        int n = held.size();
        double[] s0 = new double[n], w = new double[n], v0 = new double[n], vw = new double[n], g = new double[n];
        for (int k = 0; k < n; k++) {
            NiftyTradeLog t = held.get(k);
            s0[k] = spot(closes, t, before);
            double close = spot(closes, t, start);
            double[] r = ranges.getOrDefault(t.getSymbol(), Map.of()).get(start);
            w[k] = r == null || !(calls || puts) ? close : calls ? Math.min(r[0], close) : Math.max(r[1], close);
            v0[k] = t.pnlAt(start, s0[k]);
            vw[k] = v0[k] + t.pnlAt(w[k]) - t.pnlAt(s0[k]);                                        // the index move, as the estimate prices it
            boolean ownExit = t.getExitTimestamp() != null && !t.getExitTimestamp().isAfter(start);   // inside this minute
            g[k] = !ownExit ? Double.POSITIVE_INFINITY
                    : w[k] == s0[k] ? 0 : Math.max(0, Math.min(1, (t.getExitSpotPrice() - s0[k]) / (w[k] - s0[k])));
        }
        // The day's P&L at fraction f of the path, counting at their own exit the trades whose exit came by {@code upTo}.
        final double base = fixed;
        java.util.function.DoubleBinaryOperator day = (upTo, f) -> {
            double sum = base;
            for (int k = 0; k < n; k++) sum += g[k] <= upTo ? held.get(k).getEstimatedPnl() : v0[k] + f * (vw[k] - v0[k]);
            return sum;
        };
        TreeSet<Double> points = new TreeSet<>(List.of(0.0, 1.0));
        for (double x : g) if (x <= 1) points.add(x);
        Double f = null;
        double prev = 0;
        if (day.applyAsDouble(0, 0) <= -limit) f = 0.0;
        else for (double x : points.tailSet(0.0, false)) {
            double a = day.applyAsDouble(prev, prev), b = day.applyAsDouble(prev, x);   // straight between prev and x
            if (b <= -limit) { f = prev + (x - prev) * (a + limit) / (a - b); break; }
            if (day.applyAsDouble(x, x) <= -limit) { f = x; break; }                    // an own exit at x passes it
            prev = x;
        }
        int closed = 0, notTaken = 0;
        if (f == null) {   // the path never reaches it (e.g. a fill the minute's range doesn't show): close at the minute's end
            for (NiftyTradeLog t : open) {
                t.close(end, spot(closes, t, start), REASON, t.getFinalStopLoss() == null ? 0 : t.getFinalStopLoss());
                closed++;
            }
            for (NiftyTradeLog t : trades) if (entered.get(t).isAfter(end)) { t.markSkipped(REASON); notTaken++; }
            return new Stop(date, end, sum(trades, entered, end), closed, notTaken);
        }
        Instant at = start.plusSeconds(Math.round(60 * f));
        for (int k = 0; k < n; k++) {
            NiftyTradeLog t = held.get(k);
            if (g[k] <= f) continue;                                                                 // its own exit came first
            t.closeInMinute(at, start, s0[k], s0[k] + f * (w[k] - s0[k]), REASON, t.getFinalStopLoss() == null ? 0 : t.getFinalStopLoss());
            closed++;
        }
        for (NiftyTradeLog t : trades) if (entered.get(t).isAfter(start)) { t.markSkipped(REASON); notTaken++; }   // after the stop
        return new Stop(date, at, sum(trades, entered, end), closed, notTaken);
    }

    private static double sum(List<NiftyTradeLog> trades, Map<NiftyTradeLog, Instant> entered, Instant end) {
        double pnl = 0;
        for (NiftyTradeLog t : trades) if (t.getSkipReason() == null && !entered.get(t).isAfter(end)) pnl += t.getEstimatedPnl();
        return pnl;
    }

    /**
     * When the trade was really entered. Engines stamp an entry with the start of the candle it was decided on (e.g. a
     * 5-minute bar) but take that candle's close as the entry price, which is only known when it ends. So: the end of the
     * first minute from the stamp whose close is the entry price; the stamp itself when no minute matches.
     */
    static Instant enteredAt(Map<String, NavigableMap<Instant, Double>> closes, NiftyTradeLog t) {
        NavigableMap<Instant, Double> m = closes.get(t.getSymbol());
        Instant from = t.getEntryTimestamp();
        if (m != null)
            for (Map.Entry<Instant, Double> e : m.subMap(from, true, from.plusSeconds(15 * 60), false).entrySet())
                if (Math.abs(e.getValue() - t.getEntrySpotPrice()) < 0.01) return e.getKey().plusSeconds(60);
        return from;
    }

    /** The index close of the latest minute up to {@code start} for the trade's index (its entry price if none). */
    private static double spot(Map<String, NavigableMap<Instant, Double>> closes, NiftyTradeLog t, Instant start) {
        NavigableMap<Instant, Double> m = closes.get(t.getSymbol());
        Map.Entry<Instant, Double> e = m == null ? null : m.floorEntry(start);
        return e == null || e.getKey().isBefore(t.getEntryTimestamp()) ? t.getEntrySpotPrice() : e.getValue();
    }

    /** Indian grouping, e.g. ₹1,00,000. */
    static String rupees(double v) {
        String d = Long.toString(Math.round(Math.abs(v)));
        if (d.length() <= 3) return "₹" + d;
        StringBuilder out = new StringBuilder(d.substring(d.length() - 3));
        for (int i = d.length() - 3; i > 0; i -= 2) out.insert(0, d.substring(Math.max(0, i - 2), i) + ",");
        return "₹" + out;
    }

    static String signedRupees(double v) { return (v < 0 ? "−" : "+") + rupees(v); }
}
