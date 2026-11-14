package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.*;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.strategy.estimate.DayLossStop;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.model.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Runs a naive long-only simulation:
 * - BUY on first BUY signal when flat
 * - SELL on first SELL signal when long
 * No short-selling, no position sizing beyond all-in.
 *
 * Add more strategy types by extending the switch statement.
 */
@Component
public class BacktestEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);
    /** More separate runs of missing days than this: simulate the whole window once instead. */
    static final int MAX_BLOCKS = 4;

    private final MarketDataClient           mdClient;
    private final IndicatorClient            indClient;
    private final NiftyOpeningBreakoutEngine niftyEngine;
    private final PreviousRangeEngine        previousRangeEngine;
    private final GapFillEngine              gapFillEngine;
    private final CprEngine                  cprEngine;
    private final EmaCrossEngine             emaCrossEngine;
    private final com.tradeanalysis.strategy.estimate.CapitalFit capitalFit;
    private final BacktestDayCache dayCache;

    public BacktestEngine(MarketDataClient mdClient, IndicatorClient indClient,
                          NiftyOpeningBreakoutEngine niftyEngine, PreviousRangeEngine previousRangeEngine,
                          com.tradeanalysis.strategy.estimate.CapitalFit capitalFit, GapFillEngine gapFillEngine,
                          CprEngine cprEngine, EmaCrossEngine emaCrossEngine, BacktestDayCache dayCache) {
        this.dayCache = dayCache;
        this.gapFillEngine = gapFillEngine;
        this.cprEngine = cprEngine;
        this.emaCrossEngine = emaCrossEngine;
        this.mdClient    = mdClient;
        this.indClient   = indClient;
        this.niftyEngine = niftyEngine;
        this.previousRangeEngine = previousRangeEngine;
        this.capitalFit  = capitalFit;
    }

    public BacktestOutput run(Strategy strategy, String symbol, int lookbackDays, double capital) {
        return run(strategy, symbol, lookbackDays, capital, null);
    }

    /**
     * @param maxDayLoss the user's max day loss, applied as live trading applies it (see DayLossStop); null = none.
     *                   Only option strategies (NIFTY_OPENING_BREAKOUT) use it.
     */
    public BacktestOutput run(Strategy strategy, String symbol, int lookbackDays, double capital, Double maxDayLoss) {
        if ("NIFTY_OPENING_BREAKOUT".equals(strategy.getType())) {
            return runNiftyBreakout(strategy, symbol, lookbackDays, capital, maxDayLoss);
        }
        PriceSeries series = mdClient.getSeries(symbol, Interval.DAY_1, lookbackDays);
        List<Signal> signals = generateSignals(strategy, series);

        double cash = capital, shares = 0, peak = capital;
        double maxDD = 0, buyPrice = 0;
        int total = 0, wins = 0;
        List<Double> returns = new ArrayList<>();

        for (Signal sig : signals) {
            double price = sig.price().doubleValue();
            if (sig.type() == Signal.SignalType.BUY && cash > 0) {
                shares = cash / price;
                buyPrice = price;
                cash = 0;
                total++;
            } else if (sig.type() == Signal.SignalType.SELL && shares > 0) {
                cash = shares * price;
                double ret = (price - buyPrice) / buyPrice;
                returns.add(ret);
                if (ret > 0) wins++;
                shares = 0;
                total++;
            }
            double equity = cash + shares * price;
            if (equity > peak) peak = equity;
            double dd = (peak - equity) / peak * 100;
            if (dd > maxDD) maxDD = dd;
        }
        double finalEquity = cash + shares * (series.candles().isEmpty() ? 0
                : series.candles().get(series.candles().size() - 1).close().doubleValue());
        double totalReturn = (finalEquity - capital) / capital * 100;
        double sharpe = sharpe(returns);

        return new BacktestOutput(finalEquity, totalReturn, maxDD, total, wins, sharpe, signals, List.of());
    }

    /** Milliseconds spent per stage, for the BACKTEST_TIMING log line. */
    private static final class Timing {
        long fetch5m, fetch1m, fetchDays, simulate;
        static long ms(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }
    }

    private BacktestOutput runNiftyBreakout(Strategy strategy, String symbol, int lookbackDays, double capital, Double maxDayLoss) {
        long started = System.nanoTime();
        Timing timing = new Timing();
        // Calendar-day window, starting at IST midnight so the first day's 09:15 candle is included.
        Instant to   = Instant.now();
        LocalDate today = LocalDate.ofInstant(to, IST);
        LocalDate tradeFrom = today.minusDays(lookbackDays);
        Params params = new Params(toObjectMap(strategy.getParameters()));

        // Days already simulated by an earlier run are reused; only the rest are simulated (see BacktestDayCache).
        // The EMA crossover carries its EMAs across the whole loaded window, so a day depends on where the window
        // starts: it always runs in full.
        boolean cacheable = !EmaCrossEngine.handles(params);
        String key = cacheable ? dayCache.key(strategy.getId(), symbol, strategy.getParameters()) : null;
        Map<LocalDate, BacktestDayCache.Day> stored = cacheable ? dayCache.load(key, tradeFrom, today.minusDays(1)) : Map.of();
        List<LocalDate> sessions = cacheable && !stored.isEmpty() ? sessions(symbol, tradeFrom, today.minusDays(1), timing) : List.of();

        List<NiftyOpeningBreakoutEngine.BreakoutResult> simulated = new ArrayList<>();
        Map<LocalDate, BacktestDayCache.Day> reused = new TreeMap<>();
        List<LocalDate[]> blocks = sessions.isEmpty() ? null : missingBlocks(sessions, stored.keySet(), tradeFrom, today);
        if (blocks == null || blocks.size() > MAX_BLOCKS) {
            simulated.add(simulate(strategy, symbol, params, tradeFrom, today, to, timing));   // the whole window
        } else {
            for (LocalDate d : sessions) if (stored.containsKey(d)) reused.put(d, stored.get(d));
            for (LocalDate[] b : blocks) {
                if (b[1].isBefore(today) || hasPastSession(sessions, b[0], today)) { simulated.add(simulate(strategy, symbol, params, b[0], b[1], to, timing)); continue; }
                // Only today is left (every past day is stored). On a weekend, a holiday or before 09:15 it has no
                // candles, and market-data answers an empty range with an error: that just means nothing to simulate.
                try { simulated.add(simulate(strategy, symbol, params, b[0], b[1], to, timing)); }
                catch (RuntimeException e) { log.debug("BACKTEST_CACHE nothing to simulate for {}..{}: {}", b[0], b[1], e.getMessage()); }
            }
        }

        List<BacktestDayCache.Day> fresh = byDay(simulated);
        if (cacheable) dayCache.store(key, fresh, today);   // before the capital limit resizes the trades
        NiftyOpeningBreakoutEngine.BreakoutResult result = assemble(reused.values(), fresh);

        // Only the lots the free capital can buy at each entry, and no trade when not even one lot fits.
        com.tradeanalysis.strategy.estimate.CapitalFit.Fit fit = capitalFit.fit(result.tradeLogs(), capital);
        // Then the user's max day loss: open trades closed and later entries not taken once the day's loss reaches it.
        DayLossStop.Result stop = DayLossStop.apply(fit.kept(), maxDayLoss, dayLossMinutes(fit.kept(), maxDayLoss));
        List<NiftyTradeLog> logs = stop.kept();
        int total = logs.size();
        int wins  = (int) logs.stream().filter(t -> t.getEstimatedPnl() > 0).count();
        double totalPnl = logs.stream().mapToDouble(NiftyTradeLog::getEstimatedPnl).sum();
        double totalReturn = capital > 0 ? (totalPnl / capital) * 100 : 0;

        log.info("BACKTEST_TIMING strategy={} {} days={} reusedDays={} simulatedDays={} blocks={} fetch5m={}ms fetch1m={}ms fetchDays={}ms simulate={}ms total={}ms",
                strategy.getId(), symbol, lookbackDays, reused.size(), fresh.size(), simulated.size(),
                timing.fetch5m, timing.fetch1m, timing.fetchDays, timing.simulate, Timing.ms(started));
        // Stored with the run: the trades taken, and the ones the max day loss kept out (marked, counted in no total).
        List<NiftyTradeLog> withBlocked = new ArrayList<>(logs);
        withBlocked.addAll(stop.blocked());
        withBlocked.sort(Comparator.comparing(NiftyTradeLog::getEntryTimestamp));
        return new BacktestOutput(capital + totalPnl, totalReturn, maxDrawdownPct(capital, logs),
                total, wins, 0.0, result.signals(), withBlocked, withStops(result.days(), stop, maxDayLoss), fit.skipped(),
                maxDayLoss != null && maxDayLoss > 0 ? maxDayLoss : null, stop.stops().size());
    }

    /** The 1-minute candles of each index the trades are on, over their days: what DayLossStop values open trades at. */
    private Map<String, PriceSeries> dayLossMinutes(List<NiftyTradeLog> trades, Double maxDayLoss) {
        if (maxDayLoss == null || maxDayLoss <= 0 || trades.isEmpty()) return Map.of();
        LocalDate first = trades.stream().map(NiftyTradeLog::getTradeDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate last = trades.stream().map(NiftyTradeLog::getTradeDate).max(Comparator.naturalOrder()).orElseThrow();
        return minuteCandles(trades.stream().map(NiftyTradeLog::getSymbol).distinct().toList(), first, last);
    }

    /** Each index's 1-minute candles from {@code from} to {@code until} (inclusive, up to now). */
    public Map<String, PriceSeries> minuteCandles(Collection<String> symbols, LocalDate from, LocalDate until) {
        Map<String, PriceSeries> out = new HashMap<>();
        Instant now = Instant.now();
        Instant end = until.plusDays(1).atStartOfDay(IST).toInstant().minusSeconds(1);
        for (String sym : new LinkedHashSet<>(symbols))
            out.put(sym, CandleMemo.getRange(mdClient, sym, Interval.MINUTE_1, from.atStartOfDay(IST).toInstant(),
                    end.isAfter(now) ? now : end));
        return out;
    }

    /** The day reports, with why on the days the max day loss was reached. */
    static List<NiftyOpeningBreakoutEngine.DayReport> withStops(List<NiftyOpeningBreakoutEngine.DayReport> days,
                                                                DayLossStop.Result stop, Double limit) {
        if (stop.stops().isEmpty()) return days;
        Map<LocalDate, DayLossStop.Stop> byDate = stop.byDate();
        return days.stream().map(d -> {
            DayLossStop.Stop s = byDate.get(d.date());
            if (s == null) return d;
            String why = s.describe(limit);
            return new NiftyOpeningBreakoutEngine.DayReport(d.date(), d.gapPercent(), d.status(),
                    d.reason() == null || d.reason().isBlank() ? why : d.reason() + ". " + why, d.trades() - s.blocked());
        }).toList();
    }

    /** Simulates the trading days from {@code from} to {@code until} (inclusive; today runs up to now). */
    private NiftyOpeningBreakoutEngine.BreakoutResult simulate(Strategy strategy, String symbol, Params params,
                                                                LocalDate from, LocalDate until, Instant now, Timing timing) {
        // 5-min data starts a few days earlier only to give the first day its history (previous close, SMA, ...).
        Instant end = until.isBefore(LocalDate.ofInstant(now, IST)) ? until.plusDays(1).atStartOfDay(IST).toInstant().minusSeconds(1) : now;
        long t = System.nanoTime();
        PriceSeries series = CandleMemo.getRange(mdClient, symbol, Interval.MINUTE_5,
                from.minusDays(NiftyOpeningBreakoutEngine.warmupDays(params)).atStartOfDay(IST).toInstant(), end);
        timing.fetch5m += Timing.ms(t);
        t = System.nanoTime();
        PriceSeries minutes = CandleMemo.getRange(mdClient, symbol, Interval.MINUTE_1, from.atStartOfDay(IST).toInstant(), end);
        timing.fetch1m += Timing.ms(t);
        t = System.nanoTime();
        NiftyOpeningBreakoutEngine.BreakoutResult r = engineFor(params, series, minutes, strategy.getId(), now, from);
        timing.simulate += Timing.ms(t);
        return r;
    }

    /**
     * Which engine runs is decided by the strategy's parameters: referenceMode=PREVIOUS_DAY goes to the previous-day
     * engine, GAP_FILL, CPR and EMA_CROSS to theirs; everything else runs on the 09:15 reference candle.
     */
    private NiftyOpeningBreakoutEngine.BreakoutResult engineFor(Params params, PriceSeries series, PriceSeries minutes,
                                                                 Long strategyId, Instant to, LocalDate tradeFrom) {
        if (PreviousRangeEngine.handles(params)) {
            PreviousRangeEngine.validateParams(params);
            return previousRangeEngine.run(series, minutes, params, strategyId, to, tradeFrom);
        } else if (GapFillEngine.handles(params)) {   // referenceMode=GAP_FILL: back into the opening gap
            GapFillEngine.validateParams(params);
            return gapFillEngine.run(series, minutes, params, strategyId, to, tradeFrom);
        } else if (CprEngine.handles(params)) {   // referenceMode=CPR: break of yesterday's R1 / S1
            CprEngine.validateParams(params);
            return cprEngine.run(series, minutes, params, strategyId, to, tradeFrom);
        } else if (EmaCrossEngine.handles(params)) {   // referenceMode=EMA_CROSS: 5-min EMA crossover
            EmaCrossEngine.validateParams(params);
            return emaCrossEngine.run(series, minutes, params, strategyId, to, tradeFrom);
        }
        return niftyEngine.run(series, minutes, params, strategyId, to, tradeFrom);
    }

    /** Whether a past trading day (before today) falls in the block starting at {@code from}. */
    static boolean hasPastSession(List<LocalDate> sessions, LocalDate from, LocalDate today) {
        return sessions.stream().anyMatch(d -> !d.isBefore(from) && d.isBefore(today));
    }

    /** The index's trading days from its daily candles (empty if they can't be had: the window then runs in full). */
    private List<LocalDate> sessions(String symbol, LocalDate from, LocalDate to, Timing timing) {
        if (to.isBefore(from)) return List.of();
        long t = System.nanoTime();
        try {
            PriceSeries daily = CandleMemo.getRange(mdClient, symbol, Interval.DAY_1, from.atStartOfDay(IST).toInstant(),
                    to.plusDays(1).atStartOfDay(IST).toInstant().minusSeconds(1));
            return daily.candles().stream().map(c -> c.timestamp().atZone(IST).toLocalDate()).distinct().sorted().toList();
        } catch (RuntimeException e) {
            log.warn("BACKTEST_CACHE no daily candles for {} {}..{}: {}", symbol, from, to, e.getMessage());
            return List.of();
        } finally {
            timing.fetchDays += Timing.ms(t);
        }
    }

    /**
     * Daily candles may start later than the minute candles the engines trade on (Bank Nifty's start in March 2026,
     * its minute candles months earlier). A first session more than this many days after the window starts means the
     * daily candles don't reach back that far, not a long weekend or holiday.
     */
    static final int MAX_SESSION_GAP_DAYS = 4;

    /**
     * The runs of consecutive trading days (from, until) that still need simulating: the start of the window when
     * the daily candles don't reach back to it (those days are simulated, never dropped), past sessions not stored,
     * then today (always simulated, it isn't over), joined to the run before it when that run ends on the last session.
     */
    static List<LocalDate[]> missingBlocks(List<LocalDate> sessions, Set<LocalDate> stored, LocalDate from, LocalDate today) {
        List<LocalDate[]> blocks = new ArrayList<>();
        if (!sessions.isEmpty() && sessions.get(0).isAfter(from.plusDays(MAX_SESSION_GAP_DAYS)))
            blocks.add(new LocalDate[]{from, sessions.get(0).minusDays(1)});
        LocalDate start = null, prev = null;
        for (LocalDate d : sessions) {
            if (stored.contains(d)) {
                if (start != null) { blocks.add(new LocalDate[]{start, prev}); start = null; }
            } else if (start == null) start = d;
            prev = d;
        }
        if (start != null) blocks.add(new LocalDate[]{start, today});   // runs on to today
        else blocks.add(new LocalDate[]{sessions.isEmpty() ? today : prev.plusDays(1), today});
        return blocks;
    }

    /** One simulation's output split by day, for storing. */
    static List<BacktestDayCache.Day> byDay(List<NiftyOpeningBreakoutEngine.BreakoutResult> results) {
        Map<LocalDate, BacktestDayCache.Day> days = new TreeMap<>();
        for (NiftyOpeningBreakoutEngine.BreakoutResult r : results) {
            for (NiftyOpeningBreakoutEngine.DayReport d : r.days())
                days.put(d.date(), new BacktestDayCache.Day(d.date(), d, new ArrayList<>(), new ArrayList<>()));
            for (NiftyTradeLog t : r.tradeLogs())
                days.computeIfAbsent(t.getTradeDate(), k -> new BacktestDayCache.Day(k, null, new ArrayList<>(), new ArrayList<>())).trades().add(t);
            for (Signal s : r.signals()) {
                LocalDate k = s.timestamp().atZone(IST).toLocalDate();
                days.computeIfAbsent(k, x -> new BacktestDayCache.Day(x, null, new ArrayList<>(), new ArrayList<>())).signals().add(s);
            }
        }
        return new ArrayList<>(days.values());
    }

    /** Reused and newly simulated days as one result, in day order (trades by day and number, signals by time). */
    static NiftyOpeningBreakoutEngine.BreakoutResult assemble(Collection<BacktestDayCache.Day> reused, Collection<BacktestDayCache.Day> fresh) {
        TreeMap<LocalDate, BacktestDayCache.Day> all = new TreeMap<>();
        reused.forEach(d -> all.put(d.date(), d));
        fresh.forEach(d -> all.put(d.date(), d));
        List<Signal> signals = new ArrayList<>();
        List<NiftyTradeLog> trades = new ArrayList<>();
        List<NiftyOpeningBreakoutEngine.DayReport> days = new ArrayList<>();
        for (BacktestDayCache.Day d : all.values()) {
            if (d.report() != null) days.add(d.report());
            d.trades().stream().sorted(Comparator.comparingInt(NiftyTradeLog::getTradeNumber)).forEach(trades::add);
            d.signals().stream().sorted(Comparator.comparing(Signal::timestamp)).forEach(signals::add);
        }
        return new NiftyOpeningBreakoutEngine.BreakoutResult(signals, trades, days);
    }

    /** Largest peak-to-trough fall in account equity (capital + cumulative P&L), as % of the peak. */
    static double maxDrawdownPct(double capital, List<NiftyTradeLog> logs) {
        double equity = capital, peak = capital, maxDD = 0;
        for (NiftyTradeLog t : logs) {
            equity += t.getEstimatedPnl();
            if (equity > peak) peak = equity;
            double dd = peak > 0 ? (peak - equity) / peak * 100 : 0;
            if (dd > maxDD) maxDD = dd;
        }
        return maxDD;
    }

    private List<Signal> generateSignals(Strategy strategy, PriceSeries series) {
        return switch (strategy.getType()) {
            case "SMA_CROSSOVER" -> smaCrossover(series, new Params(toObjectMap(strategy.getParameters())));
            case "RSI_THRESHOLD" -> rsiThreshold(series, new Params(toObjectMap(strategy.getParameters())));
            case "MACD_SIGNAL"   -> macdSignal(series, new Params(toObjectMap(strategy.getParameters())));
            default -> throw new IllegalArgumentException("Unknown strategy type: " + strategy.getType());
        };
    }

    private List<Signal> smaCrossover(PriceSeries series, Params p) {
        int fast = p.intOr("fast", 10), slow = p.intOr("slow", 30);
        List<Double> fastSma = computeSma(series, fast);
        List<Double> slowSma = computeSma(series, slow);
        List<Signal> signals = new ArrayList<>();
        for (int i = 1; i < series.candles().size(); i++) {
            Double f0 = fastSma.get(i - 1), f1 = fastSma.get(i);
            Double s0 = slowSma.get(i - 1), s1 = slowSma.get(i);
            if (f0 == null || s0 == null || f1 == null || s1 == null) continue;
            Candle c = series.candles().get(i);
            if (f0 <= s0 && f1 > s1) signals.add(new Signal(c.timestamp(), Signal.SignalType.BUY, c.close(), "SMA cross up"));
            if (f0 >= s0 && f1 < s1) signals.add(new Signal(c.timestamp(), Signal.SignalType.SELL, c.close(), "SMA cross down"));
        }
        return signals;
    }

    private List<Signal> rsiThreshold(PriceSeries series, Params p) {
        int period = p.intOr("period", 14);
        double oversold = p.doubleOr("oversold", 30), overbought = p.doubleOr("overbought", 70);
        IndicatorResult rsiResult = indClient.compute(new IndicatorRequest("RSI", series, Map.of("period", period)));
        List<Double> rsi = rsiResult.lines().get("rsi");
        List<Signal> signals = new ArrayList<>();
        for (int i = 1; i < series.candles().size(); i++) {
            Double r0 = rsi.get(i - 1), r1 = rsi.get(i);
            if (r0 == null || r1 == null) continue;
            Candle c = series.candles().get(i);
            if (r0 <= oversold && r1 > oversold) signals.add(new Signal(c.timestamp(), Signal.SignalType.BUY, c.close(), "RSI cross above oversold"));
            if (r0 >= overbought && r1 < overbought) signals.add(new Signal(c.timestamp(), Signal.SignalType.SELL, c.close(), "RSI cross below overbought"));
        }
        return signals;
    }

    private List<Signal> macdSignal(PriceSeries series, Params p) {
        IndicatorResult macd = indClient.compute(new IndicatorRequest("MACD", series, Map.of()));
        List<Double> hist = macd.lines().get("histogram");
        List<Signal> signals = new ArrayList<>();
        for (int i = 1; i < series.candles().size(); i++) {
            Double h0 = hist.get(i - 1), h1 = hist.get(i);
            if (h0 == null || h1 == null) continue;
            Candle c = series.candles().get(i);
            if (h0 < 0 && h1 >= 0) signals.add(new Signal(c.timestamp(), Signal.SignalType.BUY, c.close(), "MACD histogram cross +"));
            if (h0 > 0 && h1 <= 0) signals.add(new Signal(c.timestamp(), Signal.SignalType.SELL, c.close(), "MACD histogram cross -"));
        }
        return signals;
    }

    private List<Double> computeSma(PriceSeries series, int period) {
        IndicatorResult r = indClient.compute(new IndicatorRequest("SMA", series, Map.of("period", period)));
        return r.lines().get("sma");
    }

    private double sharpe(List<Double> returns) {
        if (returns.size() < 2) return 0;
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = returns.stream().mapToDouble(r -> Math.pow(r - mean, 2)).sum() / (returns.size() - 1);
        double std = Math.sqrt(var);
        return std == 0 ? 0 : (mean / std) * Math.sqrt(252);
    }

    private Map<String, Object> toObjectMap(Map<String, String> m) {
        return m == null ? Map.of() : new HashMap<>(m);
    }

    public record BacktestOutput(double finalCapital, double totalReturn, double maxDrawdown,
                                  int totalTrades, int winningTrades, double sharpeRatio,
                                  List<Signal> signals, List<NiftyTradeLog> tradeLogs,
                                  List<NiftyOpeningBreakoutEngine.DayReport> days,
                                  /** Trades left out because the capital that was free at their entry could not buy a lot. */
                                  int skippedTrades,
                                  /** The max day loss applied (null = none) and on how many days it was reached. */
                                  Double maxDayLoss, int dayLossStops) {
        public BacktestOutput(double finalCapital, double totalReturn, double maxDrawdown, int totalTrades,
                              int winningTrades, double sharpeRatio, List<Signal> signals, List<NiftyTradeLog> tradeLogs,
                              List<NiftyOpeningBreakoutEngine.DayReport> days, int skippedTrades) {
            this(finalCapital, totalReturn, maxDrawdown, totalTrades, winningTrades, sharpeRatio, signals, tradeLogs, days, skippedTrades, null, 0);
        }

        public BacktestOutput(double finalCapital, double totalReturn, double maxDrawdown, int totalTrades,
                              int winningTrades, double sharpeRatio, List<Signal> signals, List<NiftyTradeLog> tradeLogs,
                              List<NiftyOpeningBreakoutEngine.DayReport> days) {
            this(finalCapital, totalReturn, maxDrawdown, totalTrades, winningTrades, sharpeRatio, signals, tradeLogs, days, 0);
        }

        public BacktestOutput(double finalCapital, double totalReturn, double maxDrawdown, int totalTrades,
                              int winningTrades, double sharpeRatio, List<Signal> signals, List<NiftyTradeLog> tradeLogs) {
            this(finalCapital, totalReturn, maxDrawdown, totalTrades, winningTrades, sharpeRatio, signals, tradeLogs, List.of(), 0);
        }
    }
}
