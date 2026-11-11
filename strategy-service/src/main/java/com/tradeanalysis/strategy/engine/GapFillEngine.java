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
 * Gap fill: after an opening gap, trade back towards yesterday's close once price re-enters the gap.
 *
 * Rules (backtests, and paper/live trading through {@link #runLive}):
 * - The gap is today's 09:15 open against yesterday's last close, in % of that close; it must be at least gapPct
 *   (default 0.2) and below maxGapPct (default 2). Gap-up days trade PE, gap-down days trade CE (directions PE, CE or
 *   BOTH, default BOTH).
 * - Skipped when the 09:15-09:20 candle already reaches yesterday's close (the gap filled at once).
 * - Entry: the first 5-min candle after 09:15 that closes back past today's open, into the gap (a fresh cross: the
 *   previous close was on the open's far side), before entryUntil (default 14:00), once a day. No entry once the gap
 *   has filled.
 * - Target: gapFillTarget of the gap filled, counted from today's open (default 0.5 = halfway to yesterday's close,
 *   1 = the full fill). No trade when the entry is already past it.
 * - Stop: the 09:15 candle's high (PE) / low (CE), but never more than maxStopPoints from entry when that is set.
 *   Checked on 1-min candles, stop first, then target; square-off at 15:20.
 *
 * Selected by {@code referenceMode=GAP_FILL}; see {@link BacktestEngine}. Other parameters used: lots (2),
 * underlying, skipDays (none), costPerTrade (0).
 */
@Component
public class GapFillEngine {

    public static final String MODE = "GAP_FILL";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime REF_CANDLE_TIME = LocalTime.of(9, 15);
    private static final LocalTime EOD_EXIT_TIME = LocalTime.of(15, 20);
    private static final LocalTime DEFAULT_ENTRY_UNTIL = LocalTime.of(14, 0);

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
            double pc = prev.get(prev.size() - 1).close().doubleValue();
            Candle ref = day.stream().filter(c -> istTime(c).equals(REF_CANDLE_TIME)).findFirst().orElse(null);
            if (ref == null) { days.add(new NiftyOpeningBreakoutEngine.DayReport(date, null, "NO_DATA", "No 09:15 candle", 0)); continue; }
            double open = ref.open().doubleValue();
            double gap = (open - pc) / pc * 100;
            if (cfg.skipDays().contains(date.getDayOfWeek())) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "SKIPPED", "skipDays includes " + date.getDayOfWeek(), 0));
                continue;
            }
            boolean up = gap >= cfg.minGap() && gap < cfg.maxGap(), down = -gap >= cfg.minGap() && -gap < cfg.maxGap();
            boolean call = down;   // gap-down: CE back up into the gap; gap-up: PE back down
            if (!(up && cfg.allowPe()) && !(down && cfg.allowCe())) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE", String.format(
                        "Gap %.2f%% is not a %s between %.2f%% and %.2f%%", gap,
                        cfg.allowCe() && cfg.allowPe() ? "gap" : cfg.allowPe() ? "gap-up" : "gap-down", cfg.minGap(), cfg.maxGap()), 0));
                continue;
            }
            if (call ? ref.high().doubleValue() >= pc : ref.low().doubleValue() <= pc) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE", "The gap filled in the first candle", 0));
                continue;
            }

            // The first 5-min close back past today's open, fresh from the gap's far side, before the gap fills.
            Candle entry = null;
            double prevClose = ref.close().doubleValue();
            String noEntry = "No close back into the gap before the entry cutoff";
            for (Candle c : day) {
                if (!c.timestamp().isAfter(ref.timestamp())) continue;
                if (c.timestamp().plus(Duration.ofMinutes(5)).isAfter(now)) break;   // still forming
                LocalTime t = istTime(c);
                if (!t.isBefore(cfg.entryUntil()) || !t.isBefore(EOD_EXIT_TIME)) break;
                if (call ? c.high().doubleValue() >= pc : c.low().doubleValue() <= pc) { noEntry = "The gap filled before an entry"; break; }
                double close = c.close().doubleValue();
                if (call ? prevClose <= open && close > open : prevClose >= open && close < open) { entry = c; break; }
                prevClose = close;
            }
            if (entry == null) { days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE", noEntry, 0)); continue; }

            double entrySpot = entry.close().doubleValue();
            double target = open - cfg.fillTarget() * (open - pc);
            if (call ? target <= entrySpot : target >= entrySpot) {
                days.add(new NiftyOpeningBreakoutEngine.DayReport(date, gap, "NO_TRADE", "Entry already past the target", 0));
                continue;
            }
            double stop = call ? ref.low().doubleValue() : ref.high().doubleValue();
            if (cfg.maxStopPoints() > 0) stop = call ? Math.max(stop, entrySpot - cfg.maxStopPoints()) : Math.min(stop, entrySpot + cfg.maxStopPoints());
            String side = call ? "CE" : "PE";
            int strike = cfg.underlying().strike(call, entrySpot);
            NiftyTradeLog trade = new NiftyTradeLog(strategyId, series.symbol(), date, 1, call ? "BULLISH" : "BEARISH",
                    ref.high().doubleValue(), ref.low().doubleValue(), ref.timestamp(), entry.timestamp(), entrySpot, side, strike,
                    cfg.lots(), cfg.lotSize(), stop);
            trade.setContext(gap, cfg.costPerTrade());
            signals.add(new Signal(entry.timestamp(), Signal.SignalType.BUY, BigDecimal.valueOf(entrySpot), String.format(
                    "ENTRY %s | Strike %d | Gap %.2f%% | Open %.2f | PrevClose %.2f | Target %.2f | Stop %.2f", side, strike, gap, open, pc, target, stop)));

            Exit exit = exitFor(call, stop, target, entry, date, oneMin, now, closeAtEnd);
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

    /** Stop first, then target, on 1-min candles after the entry candle; square-off at 15:20 (or the last bar). */
    private Exit exitFor(boolean call, double stop, double target, Candle entry, LocalDate date,
                         NavigableMap<Instant, Candle> oneMin, Instant now, boolean closeAtEnd) {
        Instant from = entry.timestamp().plus(Duration.ofMinutes(5));
        Instant to = date.atTime(EOD_EXIT_TIME).atZone(IST).toInstant();
        Candle last = null;
        for (Candle bar : oneMin.subMap(from, true, to, true).values()) {
            if (bar.timestamp().plus(Duration.ofMinutes(1)).isAfter(now)) break;   // still forming
            if (!bar.timestamp().isBefore(to)) return new Exit(bar, bar.open().doubleValue(), "EOD_SQUAREOFF");
            last = bar;
            double o = bar.open().doubleValue();
            if (call ? bar.low().doubleValue() <= stop : bar.high().doubleValue() >= stop)
                return new Exit(bar, call ? Math.min(o, stop) : Math.max(o, stop), "SL_HIT");
            if (call ? bar.high().doubleValue() >= target : bar.low().doubleValue() <= target)
                return new Exit(bar, call ? Math.max(o, target) : Math.min(o, target), "TARGET_HIT");
        }
        if (!closeAtEnd && now.isBefore(to)) return null;
        if (last == null) return new Exit(entry, entry.close().doubleValue(), "EOD_SQUAREOFF");
        return new Exit(last, last.close().doubleValue(), "EOD_SQUAREOFF");
    }

    private static LocalTime istTime(Candle c) { return c.timestamp().atZone(IST).toLocalTime(); }

    private record Exit(Candle bar, double price, String reason) {}

    private record Settings(int lots, int lotSize, double minGap, double maxGap, double fillTarget, double maxStopPoints,
                            LocalTime entryUntil, Set<DayOfWeek> skipDays, double costPerTrade, Underlying underlying,
                            boolean allowCe, boolean allowPe) {
        static Settings from(Params p) {
            String dirs = p.stringOr("directions", "BOTH").trim().toUpperCase(Locale.ROOT);
            if (dirs.isEmpty()) dirs = "BOTH";
            if (!Set.of("CE", "PE", "BOTH").contains(dirs))
                throw new IllegalArgumentException("The gap-fill strategy trades CE (gap-down days), PE (gap-up days) or BOTH: " + dirs);
            double minGap = p.doubleOr("gapPct", 0.2), maxGap = p.doubleOr("maxGapPct", 2);
            if (minGap <= 0 || minGap >= 5) throw new IllegalArgumentException("gapPct must be above 0 and below 5: " + minGap);
            if (maxGap <= minGap) throw new IllegalArgumentException("maxGapPct must be above gapPct: " + maxGap);
            double fill = p.doubleOr("gapFillTarget", 0.5);
            if (fill <= 0 || fill > 1) throw new IllegalArgumentException("gapFillTarget must be above 0 and at most 1 (1 = the full fill): " + fill);
            double cap = p.doubleOr("maxStopPoints", 0);
            if (cap < 0) throw new IllegalArgumentException("maxStopPoints can't be negative: " + cap);
            Underlying u = Underlying.of(p.stringOr("underlying", ""));
            String until = p.stringOr("entryUntil", "").trim();
            LocalTime entryUntil;
            try { entryUntil = until.isEmpty() ? DEFAULT_ENTRY_UNTIL : LocalTime.parse(until); }
            catch (DateTimeException e) { throw new IllegalArgumentException("entryUntil must be HH:mm: " + until); }
            return new Settings(p.intOr("lots", 2), u.defaultLotSize, minGap, maxGap, fill, cap, entryUntil,
                    days(p.stringOr("skipDays", "")), p.doubleOr("costPerTrade", 0), u, !dirs.equals("PE"), !dirs.equals("CE"));
        }

        private static Set<DayOfWeek> days(String raw) {
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
    }
}
