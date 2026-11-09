package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.*;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.model.Strategy;
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

    private final MarketDataClient           mdClient;
    private final IndicatorClient            indClient;
    private final NiftyOpeningBreakoutEngine niftyEngine;
    private final PreviousRangeEngine        previousRangeEngine;
    private final com.tradeanalysis.strategy.estimate.CapitalFit capitalFit;

    public BacktestEngine(MarketDataClient mdClient, IndicatorClient indClient,
                          NiftyOpeningBreakoutEngine niftyEngine, PreviousRangeEngine previousRangeEngine,
                          com.tradeanalysis.strategy.estimate.CapitalFit capitalFit) {
        this.mdClient    = mdClient;
        this.indClient   = indClient;
        this.niftyEngine = niftyEngine;
        this.previousRangeEngine = previousRangeEngine;
        this.capitalFit  = capitalFit;
    }

    public BacktestOutput run(Strategy strategy, String symbol, int lookbackDays, double capital) {
        if ("NIFTY_OPENING_BREAKOUT".equals(strategy.getType())) {
            return runNiftyBreakout(strategy, symbol, lookbackDays, capital);
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

    private BacktestOutput runNiftyBreakout(Strategy strategy, String symbol, int lookbackDays, double capital) {
        // Calendar-day window, starting at IST midnight so the first day's 09:15 candle is included.
        // 5-min data starts a few days earlier only to give the first day a previous close (gap filter).
        Instant to   = Instant.now();
        LocalDate tradeFrom = LocalDate.now(IST).minusDays(lookbackDays);
        Instant from = tradeFrom.atStartOfDay(IST).toInstant();
        PriceSeries series = mdClient.getRange(symbol, Interval.MINUTE_5,
                tradeFrom.minusDays(7).atStartOfDay(IST).toInstant(), to);
        PriceSeries minutes = mdClient.getRange(symbol, Interval.MINUTE_1, from, to);
        Params params = new Params(toObjectMap(strategy.getParameters()));
        // Which engine runs is decided by the strategy's parameters: referenceMode=PREVIOUS_DAY goes to the
        // previous-day PE engine; everything else runs on the 09:15 reference candle as before.
        NiftyOpeningBreakoutEngine.BreakoutResult result;
        if (PreviousRangeEngine.handles(params)) {
            PreviousRangeEngine.validateParams(params);
            result = previousRangeEngine.run(series, minutes, params, strategy.getId(), to, tradeFrom);
        } else {
            result = niftyEngine.run(series, minutes, params, strategy.getId(), to, tradeFrom);
        }

        // Only the lots the free capital can buy at each entry, and no trade when not even one lot fits.
        com.tradeanalysis.strategy.estimate.CapitalFit.Fit fit = capitalFit.fit(result.tradeLogs(), capital);
        List<NiftyTradeLog> logs = fit.kept();
        int total = logs.size();
        int wins  = (int) logs.stream().filter(t -> t.getEstimatedPnl() > 0).count();
        double totalPnl = logs.stream().mapToDouble(NiftyTradeLog::getEstimatedPnl).sum();
        double totalReturn = capital > 0 ? (totalPnl / capital) * 100 : 0;

        return new BacktestOutput(capital + totalPnl, totalReturn, maxDrawdownPct(capital, logs),
                total, wins, 0.0, result.signals(), logs, result.days(), fit.skipped());
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
                                  int skippedTrades) {
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
