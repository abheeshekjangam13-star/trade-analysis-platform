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

import static com.tradeanalysis.strategy.engine.FixedStopTrade.*;

/**
 * Intraday EMA crossover on 5-minute candles: CE when the fast EMA crosses above the slow one, PE when it crosses below.
 *
 * Rules (backtests, and paper/live trading through {@link #runLive}):
 * - EMAs of 5-min closes (fastEma, default 9; slowEma, default 21), carried across days: every loaded candle feeds
 *   them, so the history before the first trading day warms them up. Seeded with the first close.
 * - Entry: the first completed 5-min candle starting at or after entryFrom (default 09:45) and before entryUntil
 *   (default 14:00) on which the fast EMA crosses the slow one in an allowed direction (fast ≤ slow on the previous
 *   candle and fast > slow on this one for CE, the reverse for PE). Enter at its close, once a day.
 * - Stop: the lowest low (CE) / highest high (PE) of the last swingBars 5-min candles, the entry candle included
 *   (default 3), but never more than maxStopPoints from entry when that is set. Target: targetPoints in favour
 *   (0 = none). Checked on 1-min candles, stop first; square-off at 15:20.
 *
 * Selected by {@code referenceMode=EMA_CROSS}; see {@link BacktestEngine}. Parameters: those of
 * {@link FixedStopTrade.Basics} (directions default BOTH), fastEma, slowEma, entryFrom, swingBars (1-20).
 */
@Component
public class EmaCrossEngine {

    public static final String MODE = "EMA_CROSS";
    private static final LocalTime DEFAULT_ENTRY_FROM = LocalTime.of(9, 45);

    public static boolean handles(Params params) {
        return MODE.equalsIgnoreCase(params.stringOr("referenceMode", "").trim());
    }

    /** Throws IllegalArgumentException with a readable message when the parameters don't fit this engine. */
    public static void validateParams(Params params) { Settings.from(params); }

    public NiftyOpeningBreakoutEngine.BreakoutResult run(PriceSeries series, PriceSeries minutes, Params params,
                                                         Long strategyId, Instant now, LocalDate tradeFrom) {
        return run(series, minutes, params, strategyId, now, tradeFrom, true);
    }

    /** Live use: the same rules, but a trade still open at {@code now} stays open (in openTrades). */
    public NiftyOpeningBreakoutEngine.BreakoutResult runLive(PriceSeries series, PriceSeries minutes, Params params,
                                                             Long strategyId, Instant now, LocalDate tradeFrom) {
        return run(series, minutes, params, strategyId, now, tradeFrom, false);
    }

    private NiftyOpeningBreakoutEngine.BreakoutResult run(PriceSeries series, PriceSeries minutes, Params params, Long strategyId,
                                                          Instant now, LocalDate tradeFrom, boolean closeAtEnd) {
        Settings cfg = Settings.from(params);
        Basics b = cfg.basics();
        // Only completed candles feed the EMAs.
        List<Candle> bars = series.candles().stream()
                .filter(c -> !c.timestamp().plus(Duration.ofMinutes(5)).isAfter(now))
                .sorted(Comparator.comparing(Candle::timestamp)).toList();
        double[] fast = ema(bars, cfg.fastEma()), slow = ema(bars, cfg.slowEma());
        NavigableMap<Instant, Candle> oneMin = new TreeMap<>();
        minutes.candles().forEach(c -> oneMin.put(c.timestamp(), c));

        // Index range of each trading day in bars.
        Map<LocalDate, int[]> dayRange = new TreeMap<>();
        for (int i = 0; i < bars.size(); i++) {
            LocalDate d = bars.get(i).timestamp().atZone(IST).toLocalDate();
            int idx = i;
            dayRange.compute(d, (k, v) -> v == null ? new int[]{idx, idx} : new int[]{v[0], idx});
        }

        List<Signal> signals = new ArrayList<>();
        List<NiftyTradeLog> logs = new ArrayList<>(), openTrades = new ArrayList<>();
        List<NiftyOpeningBreakoutEngine.DayReport> days = new ArrayList<>();

        for (var e : dayRange.entrySet()) {
            LocalDate date = e.getKey();
            if (tradeFrom != null && date.isBefore(tradeFrom)) continue;
            int first = e.getValue()[0], lastIdx = e.getValue()[1];
            if (first == 0) { days.add(new NiftyOpeningBreakoutEngine.DayReport(date, null, "NO_DATA", "No earlier candles to warm up the EMAs", 0)); continue; }
            double prevClose = bars.get(first - 1).close().doubleValue();
            Double gap = (bars.get(first).open().doubleValue() - prevClose) / prevClose * 100;
            if (b.skipDays().contains(date.getDayOfWeek())) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "SKIPPED", "skipDays includes " + date.getDayOfWeek(), 0));
                continue;
            }

            int entryIdx = -1;
            boolean call = false;
            for (int i = first; i <= lastIdx; i++) {
                LocalTime t = istTime(bars.get(i));
                if (t.isBefore(cfg.entryFrom())) continue;
                if (!t.isBefore(b.entryUntil()) || !t.isBefore(EOD_EXIT_TIME)) break;
                boolean up = fast[i - 1] <= slow[i - 1] && fast[i] > slow[i];
                boolean down = fast[i - 1] >= slow[i - 1] && fast[i] < slow[i];
                if (up && b.allowCe()) { entryIdx = i; call = true; break; }
                if (down && b.allowPe()) { entryIdx = i; call = false; break; }
            }
            if (entryIdx < 0) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE", "No EMA " + cfg.fastEma() + "/" + cfg.slowEma()
                        + " crossover between " + cfg.entryFrom() + " and " + b.entryUntil(), 0));
                continue;
            }

            Candle entry = bars.get(entryIdx);
            double entrySpot = entry.close().doubleValue();
            double swing = call ? Double.MAX_VALUE : -Double.MAX_VALUE;
            for (int k = Math.max(0, entryIdx - cfg.swingBars() + 1); k <= entryIdx; k++)
                swing = call ? Math.min(swing, bars.get(k).low().doubleValue()) : Math.max(swing, bars.get(k).high().doubleValue());
            double stop = b.capStop(call, entrySpot, swing);
            String side = call ? "CE" : "PE";
            int strike = b.underlying().strike(call, entrySpot);
            NiftyTradeLog trade = new NiftyTradeLog(strategyId, series.symbol(), date, 1, call ? "BULLISH" : "BEARISH",
                    fast[entryIdx], slow[entryIdx], bars.get(first).timestamp(), entry.timestamp(), entrySpot, side, strike,
                    b.lots(), b.lotSize(), stop);
            trade.setContext(gap, b.costPerTrade());
            signals.add(new Signal(entry.timestamp(), Signal.SignalType.BUY, BigDecimal.valueOf(entrySpot), String.format(
                    "ENTRY %s | Strike %d | EMA%d %.2f | EMA%d %.2f | Stop %.2f", side, strike, cfg.fastEma(), fast[entryIdx],
                    cfg.slowEma(), slow[entryIdx], stop)));

            Exit exit = exitFor(call, stop, entrySpot, b.targetPoints(), entry, date, oneMin, now, closeAtEnd);
            if (exit == null) {   // live: still open
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

    /** Exponential moving average of closes, k = 2 / (n + 1), seeded with the first close. */
    static double[] ema(List<Candle> bars, int n) {
        double[] out = new double[bars.size()];
        double k = 2.0 / (n + 1);
        for (int i = 0; i < bars.size(); i++) {
            double c = bars.get(i).close().doubleValue();
            out[i] = i == 0 ? c : out[i - 1] + k * (c - out[i - 1]);
        }
        return out;
    }

    private record Settings(Basics basics, int fastEma, int slowEma, LocalTime entryFrom, int swingBars) {
        static Settings from(Params p) {
            int f = p.intOr("fastEma", 9), s = p.intOr("slowEma", 21), swing = p.intOr("swingBars", 3);
            if (f < 2 || f > 200) throw new IllegalArgumentException("fastEma must be 2-200: " + f);
            if (s <= f || s > 400) throw new IllegalArgumentException("slowEma must be above fastEma and at most 400: " + s);
            if (swing < 1 || swing > 20) throw new IllegalArgumentException("swingBars must be 1-20: " + swing);
            Basics b = Basics.from(p, "EMA crossover");
            LocalTime from = time(p, "entryFrom", DEFAULT_ENTRY_FROM);
            if (!from.isBefore(b.entryUntil())) throw new IllegalArgumentException("entryFrom must be before entryUntil: " + from);
            return new Settings(b, f, s, from, swing);
        }
    }
}
