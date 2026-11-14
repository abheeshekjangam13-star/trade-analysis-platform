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
 * <p>Values use the same estimate as the trades themselves ({@link NiftyTradeLog#pnlAt}: index move × lot size × lots ×
 * 0.5, minus charges). Give it the trades that were taken (after the capital fit), with their final lots.
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
        if (limit == null || limit <= 0 || trades.isEmpty()) return Result.none(trades);
        Map<String, NavigableMap<Instant, Double>> closes = new HashMap<>();
        minutes.forEach((sym, series) -> {
            NavigableMap<Instant, Double> m = new TreeMap<>();
            if (series != null) for (Candle c : series.candles()) m.put(c.timestamp(), c.close().doubleValue());
            closes.put(sym, m);
        });

        TreeMap<LocalDate, List<NiftyTradeLog>> byDay = new TreeMap<>();
        for (NiftyTradeLog t : trades) byDay.computeIfAbsent(t.getTradeDate(), d -> new ArrayList<>()).add(t);
        List<NiftyTradeLog> kept = new ArrayList<>(), blocked = new ArrayList<>();
        List<Stop> stops = new ArrayList<>();
        for (Map.Entry<LocalDate, List<NiftyTradeLog>> day : byDay.entrySet()) {
            List<NiftyTradeLog> list = new ArrayList<>(day.getValue());
            list.sort(Comparator.comparing(NiftyTradeLog::getEntryTimestamp));
            Stop stop = stopDay(day.getKey(), list, limit, closes);
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
                else { open.add(t); pnl += t.pnlAt(spot(closes, t, start)); }
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
