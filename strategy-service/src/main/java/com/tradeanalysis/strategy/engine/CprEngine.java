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

import static com.tradeanalysis.strategy.engine.FixedStopTrade.*;

/**
 * Pivot (CPR) breakout: trade a break of yesterday's R1 or S1, with the stop at the pivot.
 *
 * Rules (backtests, and paper/live trading through {@link #runLive}):
 * - Levels from yesterday's high H, low L and close C: pivot P = (H + L + C) / 3, BC = (H + L) / 2, TC = 2P − BC
 *   (the CPR band is BC to TC), R1 = 2P − L, S1 = 2P − H.
 * - Optional narrow-CPR filter: with maxCprWidthPct set, trade only when |TC − BC| is at most that % of P.
 * - The day must open (09:15 open) between S1 and R1.
 * - Entry: the first 5-min candle after 09:15 that closes above R1 (CE) or below S1 (PE), a fresh cross, before
 *   entryUntil (default 14:00), once a day.
 * - Stop: the pivot, but never more than maxStopPoints from entry when that is set. Target: targetPoints in favour
 *   (0 = none). Checked on 1-min candles, stop first; square-off at 15:20.
 *
 * Selected by {@code referenceMode=CPR}; see {@link BacktestEngine}. Parameters: those of {@link FixedStopTrade.Basics}
 * (directions default BOTH) and maxCprWidthPct (0 = any width).
 */
@Component
public class CprEngine {

    public static final String MODE = "CPR";
    private static final LocalTime REF_CANDLE_TIME = LocalTime.of(9, 15);

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
        Map<LocalDate, List<Candle>> byDay = series.candles().stream()
                .collect(Collectors.groupingBy(c -> c.timestamp().atZone(IST).toLocalDate(), TreeMap::new, Collectors.toList()));
        NavigableMap<Instant, Candle> oneMin = new TreeMap<>();
        minutes.candles().forEach(c -> oneMin.put(c.timestamp(), c));

        List<Signal> signals = new ArrayList<>();
        List<NiftyTradeLog> logs = new ArrayList<>(), openTrades = new ArrayList<>();
        List<NiftyOpeningBreakoutEngine.DayReport> days = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>(byDay.keySet());

        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = dates.get(i);
            if (tradeFrom != null && date.isBefore(tradeFrom)) continue;
            if (i == 0) { days.add(new NiftyOpeningBreakoutEngine.DayReport(date, null, "NO_DATA", "No previous day in the data", 0)); continue; }
            List<Candle> day = byDay.get(date).stream().sorted(Comparator.comparing(Candle::timestamp)).toList();
            List<Candle> prev = byDay.get(dates.get(i - 1)).stream().sorted(Comparator.comparing(Candle::timestamp)).toList();
            double h = prev.stream().mapToDouble(c -> c.high().doubleValue()).max().orElseThrow();
            double l = prev.stream().mapToDouble(c -> c.low().doubleValue()).min().orElseThrow();
            double cl = prev.get(prev.size() - 1).close().doubleValue();
            double pivot = (h + l + cl) / 3, bc = (h + l) / 2, tc = 2 * pivot - bc, r1 = 2 * pivot - l, s1 = 2 * pivot - h;
            double widthPct = Math.abs(tc - bc) / pivot * 100;

            Candle ref = day.stream().filter(c -> istTime(c).equals(REF_CANDLE_TIME)).findFirst().orElse(null);
            Double gap = ref == null ? null : (ref.open().doubleValue() - cl) / cl * 100;
            if (b.skipDays().contains(date.getDayOfWeek())) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "SKIPPED", "skipDays includes " + date.getDayOfWeek(), 0));
                continue;
            }
            if (ref == null) { days.add(new NiftyOpeningBreakoutEngine.DayReport(date, null, "NO_DATA", "No 09:15 candle", 0)); continue; }
            if (cfg.maxCprWidthPct() > 0 && widthPct > cfg.maxCprWidthPct()) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE",
                        String.format("CPR width %.3f%% is above %.3f%%", widthPct, cfg.maxCprWidthPct()), 0));
                continue;
            }
            double open = ref.open().doubleValue();
            if (open < s1 || open > r1) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE",
                        String.format("Opened %.2f outside S1-R1 %.2f-%.2f", open, s1, r1), 0));
                continue;
            }

            // The first 5-min close beyond R1 / S1, fresh from inside, before the entry cutoff.
            Candle entry = null;
            boolean call = false;
            double prevClose = ref.close().doubleValue();
            for (Candle c : day) {
                if (!c.timestamp().isAfter(ref.timestamp())) continue;
                if (c.timestamp().plus(Duration.ofMinutes(5)).isAfter(now)) break;   // still forming
                LocalTime t = istTime(c);
                if (!t.isBefore(b.entryUntil()) || !t.isBefore(EOD_EXIT_TIME)) break;
                double close = c.close().doubleValue();
                if (b.allowCe() && prevClose <= r1 && close > r1) { entry = c; call = true; break; }
                if (b.allowPe() && prevClose >= s1 && close < s1) { entry = c; call = false; break; }
                prevClose = close;
            }
            if (entry == null) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE", "No close beyond R1/S1 before the entry cutoff", 0));
                continue;
            }

            double entrySpot = entry.close().doubleValue();
            double stop = b.capStop(call, entrySpot, pivot);
            String side = call ? "CE" : "PE";
            int strike = b.underlying().strike(call, entrySpot);
            NiftyTradeLog trade = new NiftyTradeLog(strategyId, series.symbol(), date, 1, call ? "BULLISH" : "BEARISH", r1, s1,
                    ref.timestamp(), entry.timestamp(), entrySpot, side, strike, b.lots(), b.lotSize(), stop);
            trade.setContext(gap, b.costPerTrade());
            signals.add(new Signal(entry.timestamp(), Signal.SignalType.BUY, BigDecimal.valueOf(entrySpot), String.format(
                    "ENTRY %s | Strike %d | Pivot %.2f | R1 %.2f | S1 %.2f | CPR %.3f%% | Stop %.2f", side, strike, pivot, r1, s1, widthPct, stop)));

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

    private record Settings(Basics basics, double maxCprWidthPct) {
        static Settings from(Params p) {
            double w = p.doubleOr("maxCprWidthPct", 0);
            if (w < 0 || w > 5) throw new IllegalArgumentException("maxCprWidthPct must be 0 (any width) up to 5: " + w);
            return new Settings(Basics.from(p, "CPR"), w);
        }
    }
}
