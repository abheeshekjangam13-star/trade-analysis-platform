package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.dto.Signal;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.common.market.Underlying;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Breakout of yesterday's high-low range, for a day that opens inside it: CE above yesterday's high, PE below
 * yesterday's low.
 *
 * Rules (backtests, and paper/live trading through {@link #runLive}):
 * - Yesterday's high and low (the previous trading day in the data) are the reference range; today's 09:15 candle
 *   is used only for the opening price.
 * - The day must open (09:15 open) inside that range; otherwise no trade.
 * - Entry: the first 5-min candle that closes above yesterday's high (CE) or below yesterday's low (PE), a fresh
 *   cross, after 09:15 and before entryUntil (default 14:00), once a day. With directions BOTH the first breakout
 *   either way is taken.
 * - Stop: the other side of the range (CE: yesterday's low, PE: yesterday's high), but never more than maxStopPoints
 *   from entry when that is set (0 or missing = no cap). Target: targetPoints in favour (0 = none), checked on 1-min
 *   candles, stop first.
 * - Square-off at 15:20 IST.
 *
 * Selected by {@code referenceMode=PREVIOUS_DAY} in the strategy's parameters; see {@link BacktestEngine}.
 * Parameters used: lots (2), underlying, targetPoints (0), maxStopPoints (0 = no cap), entryUntil (14:00), skipDays (none), costPerTrade (0),
 * directions (PE when empty, so older PE-only strategies keep their results; CE, PE or BOTH).
 */
@Component
public class PreviousRangeEngine {

    public static final String MODE = "PREVIOUS_DAY";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime REF_CANDLE_TIME = LocalTime.of(9, 15);
    private static final LocalTime EOD_EXIT_TIME = LocalTime.of(15, 20);
    private static final LocalTime DEFAULT_ENTRY_UNTIL = LocalTime.of(14, 0);

    /** True when the strategy asks for this engine. */
    public static boolean handles(Params params) {
        return MODE.equalsIgnoreCase(params.stringOr("referenceMode", "").trim());
    }

    /** Throws IllegalArgumentException with a readable message when the parameters don't fit this engine. */
    public static void validateParams(Params params) {
        Settings.from(params);
    }

    public NiftyOpeningBreakoutEngine.BreakoutResult run(PriceSeries series, PriceSeries minutes, Params params,
                                                         Long strategyId, Instant now, LocalDate tradeFrom) {
        return run(series, minutes, params, strategyId, now, tradeFrom, true);
    }

    /** Live use: the same rules, but a trade still open at {@code now} stays open (in openTrades, with its stop). */
    public NiftyOpeningBreakoutEngine.BreakoutResult runLive(PriceSeries series, PriceSeries minutes, Params params,
                                                             Long strategyId, Instant now, LocalDate tradeFrom) {
        return run(series, minutes, params, strategyId, now, tradeFrom, false);
    }

    private NiftyOpeningBreakoutEngine.BreakoutResult run(PriceSeries series, PriceSeries minutes, Params params,
                                                          Long strategyId, Instant now, LocalDate tradeFrom, boolean closeAtEnd) {
        Settings cfg = Settings.from(params);

        Map<LocalDate, List<Candle>> byDay = series.candles().stream()
                .collect(Collectors.groupingBy(c -> c.timestamp().atZone(IST).toLocalDate(), TreeMap::new, Collectors.toList()));
        NavigableMap<Instant, Candle> oneMin = new TreeMap<>();
        minutes.candles().forEach(c -> oneMin.put(c.timestamp(), c));

        List<Signal> signals = new ArrayList<>();
        List<NiftyTradeLog> logs = new ArrayList<>();
        List<NiftyOpeningBreakoutEngine.DayReport> days = new ArrayList<>();
        List<NiftyTradeLog> openTrades = new ArrayList<>();

        List<LocalDate> dates = new ArrayList<>(byDay.keySet());
        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = dates.get(i);
            if (tradeFrom != null && date.isBefore(tradeFrom)) continue;
            List<Candle> day = byDay.get(date).stream().sorted(Comparator.comparing(Candle::timestamp)).toList();
            Candle ref = day.stream().filter(c -> istTime(c).equals(REF_CANDLE_TIME)).findFirst().orElse(null);

            if (i == 0) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, null, "NO_DATA", "No previous day in the data", 0));
                continue;
            }
            List<Candle> prevDay = byDay.get(dates.get(i - 1)).stream().sorted(Comparator.comparing(Candle::timestamp)).toList();
            double pHigh = prevDay.stream().mapToDouble(c -> c.high().doubleValue()).max().orElseThrow();
            double pLow = prevDay.stream().mapToDouble(c -> c.low().doubleValue()).min().orElseThrow();
            double pClose = prevDay.get(prevDay.size() - 1).close().doubleValue();

            Double gap = ref == null ? null : (ref.open().doubleValue() - pClose) / pClose * 100;
            if (cfg.skipDays().contains(date.getDayOfWeek())) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "SKIPPED", "skipDays includes " + date.getDayOfWeek(), 0));
                continue;
            }
            if (ref == null) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, null, "NO_DATA", "No 09:15 candle", 0));
                continue;
            }
            double open = ref.open().doubleValue();
            if (open < pLow || open > pHigh) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE",
                        String.format("Opened %.2f outside yesterday's range %.2f-%.2f", open, pLow, pHigh), 0));
                continue;
            }

            // The first 5-min close outside yesterday's range, fresh from inside it, before the entry cutoff.
            List<Candle> trading = day.stream().filter(c -> c.timestamp().isAfter(ref.timestamp())).toList();
            Candle entryCandle = null;
            boolean call = false;
            double prevClose = ref.close().doubleValue();
            for (Candle c : trading) {
                if (c.timestamp().plus(java.time.Duration.ofMinutes(5)).isAfter(now)) break;   // still forming
                LocalTime t = istTime(c);
                if (!t.isBefore(cfg.entryUntil()) || !t.isBefore(EOD_EXIT_TIME)) break;
                double spot = c.close().doubleValue();
                if (cfg.allowCe() && prevClose <= pHigh && spot > pHigh) { entryCandle = c; call = true; break; }
                if (cfg.allowPe() && prevClose >= pLow && spot < pLow) { entryCandle = c; call = false; break; }
                prevClose = spot;
            }
            if (entryCandle == null) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE",
                        "No close " + (cfg.allowCe() && cfg.allowPe() ? "outside yesterday's range"
                                : cfg.allowCe() ? "above yesterday's high" : "below yesterday's low") + " before the entry cutoff", 0));
                continue;
            }

            double entrySpot = entryCandle.close().doubleValue();
            String side = call ? "CE" : "PE";
            double stop = cfg.capStop(call, entrySpot, call ? pLow : pHigh);
            int strike = cfg.underlying().strike(call, entrySpot);
            NiftyTradeLog trade = new NiftyTradeLog(strategyId, series.symbol(), date, 1, call ? "BULLISH" : "BEARISH", pHigh, pLow,
                    ref.timestamp(), entryCandle.timestamp(), entrySpot, side, strike, cfg.lots(), cfg.lotSize(), stop);
            trade.setContext(gap, cfg.costPerTrade());
            signals.add(new Signal(entryCandle.timestamp(), Signal.SignalType.BUY,
                    BigDecimal.valueOf(entrySpot), String.format("ENTRY %s | Strike %d | YesterdayHigh %.2f | YesterdayLow %.2f | Stop %.2f",
                            side, strike, pHigh, pLow, stop)));

            Exit exit = exitFor(call, stop, entrySpot, cfg.targetPoints(), entryCandle, date, oneMin, now, closeAtEnd);
            if (exit == null) {   // live: still open now
                openTrades.add(trade);
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "TRADED", null, 1));
                continue;
            }
            trade.close(exit.bar().timestamp(), exit.price(), exit.reason(), stop);
            logs.add(trade);
            signals.add(new Signal(exit.bar().timestamp(), Signal.SignalType.SELL, BigDecimal.valueOf(exit.price()),
                    String.format("EXIT %s %d | %s | EntrySpot %.2f | ExitSpot %.2f", side, strike, exit.reason(), entrySpot, exit.price())));
            days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "TRADED", null, 1));
        }
        return new NiftyOpeningBreakoutEngine.BreakoutResult(signals, logs, days, openTrades);
    }

    /** Walks the 1-min candles after the entry candle: stop first, then target, else square-off at 15:20 (or the last bar). */
    private Exit exitFor(boolean call, double stop, double entryPx, double targetPoints, Candle entry, LocalDate date,
                         NavigableMap<Instant, Candle> oneMin, Instant now) {
        return exitFor(call, stop, entryPx, targetPoints, entry, date, oneMin, now, true);
    }

    /** As above; with {@code closeAtEnd} false, a trade with no exit yet before 15:20 returns null (still open). */
    private Exit exitFor(boolean call, double stop, double entryPx, double targetPoints, Candle entry, LocalDate date,
                         NavigableMap<Instant, Candle> oneMin, Instant now, boolean closeAtEnd) {
        Instant from = entry.timestamp().plus(java.time.Duration.ofMinutes(5));
        Instant to = date.atTime(EOD_EXIT_TIME).atZone(IST).toInstant();
        double target = call ? entryPx + targetPoints : entryPx - targetPoints;
        Candle last = null;
        for (Candle bar : oneMin.subMap(from, true, to, true).values()) {
            if (bar.timestamp().plus(java.time.Duration.ofMinutes(1)).isAfter(now)) break;   // still forming
            if (!bar.timestamp().isBefore(to)) return new Exit(bar, bar.open().doubleValue(), "EOD_SQUAREOFF");
            last = bar;
            double open = bar.open().doubleValue();
            if (call ? bar.low().doubleValue() <= stop : bar.high().doubleValue() >= stop)
                return new Exit(bar, call ? Math.min(open, stop) : Math.max(open, stop), "SL_HIT");
            if (targetPoints > 0 && (call ? bar.high().doubleValue() >= target : bar.low().doubleValue() <= target))
                return new Exit(bar, call ? Math.max(open, target) : Math.min(open, target), "TARGET_HIT");
        }
        if (!closeAtEnd && now.isBefore(to)) return null;   // live: nothing hit yet, the trade is still open
        // The data ends before 15:20: square off at the last bar we have (or the entry candle if there is none).
        if (last == null) {
            return new Exit(entry, entry.close().doubleValue(), "EOD_SQUAREOFF");
        }
        return new Exit(last, last.close().doubleValue(), "EOD_SQUAREOFF");
    }

    private static LocalTime istTime(Candle c) {
        return c.timestamp().atZone(IST).toLocalTime();
    }

    private record Exit(Candle bar, double price, String reason) {}

    private record Settings(int lots, int lotSize, double targetPoints, LocalTime entryUntil, Set<DayOfWeek> skipDays,
                            double costPerTrade, Underlying underlying, boolean allowCe, boolean allowPe, double maxStopPoints) {

        /** The range stop, moved closer to entry when it is further away than maxStopPoints (CE below entry, PE above). */
        double capStop(boolean call, double entry, double rangeStop) {
            if (maxStopPoints <= 0) return rangeStop;
            return call ? Math.max(rangeStop, entry - maxStopPoints) : Math.min(rangeStop, entry + maxStopPoints);
        }

        static Settings from(Params p) {
            String dirs = p.stringOr("directions", "").trim().toUpperCase(Locale.ROOT);
            if (dirs.isEmpty()) dirs = "PE";   // older previous-day strategies were PE-only
            if (!Set.of("CE", "PE", "BOTH").contains(dirs))
                throw new IllegalArgumentException("The previous-day strategy trades CE, PE or BOTH: " + dirs);
            Underlying u = Underlying.of(p.stringOr("underlying", ""));
            String until = p.stringOr("entryUntil", "").trim();
            double cap = p.doubleOr("maxStopPoints", 0);
            if (cap < 0) throw new IllegalArgumentException("maxStopPoints can't be negative: " + cap);
            LocalTime entryUntil = until.isEmpty() ? DEFAULT_ENTRY_UNTIL : parseTime("entryUntil", until);
            return new Settings(p.intOr("lots", 2), u.defaultLotSize, p.doubleOr("targetPoints", 0), entryUntil,
                    parseDays(p.stringOr("skipDays", "")), p.doubleOr("costPerTrade", 0), u,
                    !dirs.equals("PE"), !dirs.equals("CE"), cap);
        }

        private static LocalTime parseTime(String name, String v) {
            try { return LocalTime.parse(v); }
            catch (DateTimeException e) { throw new IllegalArgumentException(name + " must be HH:mm: " + v); }
        }

        private static Set<DayOfWeek> parseDays(String raw) {
            Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
            if (raw == null || raw.isBlank()) return out;
            for (String s : raw.split(",")) {
                String t = s.trim().toUpperCase(Locale.ROOT);
                if (t.isEmpty()) continue;
                String prefix = t.substring(0, Math.min(3, t.length()));
                DayOfWeek d = Arrays.stream(DayOfWeek.values()).filter(x -> x.name().startsWith(prefix)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("skipDays has an unknown weekday: " + s));
                out.add(d);
            }
            return out;
        }
    }
}
