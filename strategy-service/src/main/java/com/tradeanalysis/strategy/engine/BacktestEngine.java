package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.*;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.strategy.model.Strategy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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

    private final MarketDataClient mdClient;
    private final IndicatorClient  indClient;

    public BacktestEngine(MarketDataClient mdClient, IndicatorClient indClient) {
        this.mdClient  = mdClient;
        this.indClient = indClient;
    }

    public BacktestOutput run(Strategy strategy, String symbol, int lookbackDays, double capital) {
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

        return new BacktestOutput(finalEquity, totalReturn, maxDD, total, wins, sharpe, signals);
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
                                  List<Signal> signals) {}
}
