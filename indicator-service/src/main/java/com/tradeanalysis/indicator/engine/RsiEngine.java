package com.tradeanalysis.indicator.engine;

import com.tradeanalysis.common.dto.IndicatorResult;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RsiEngine implements IndicatorEngine {

    @Override public String name() { return "RSI"; }

    @Override
    public IndicatorResult compute(PriceSeries series, Params params) {
        int period = params.intOr("period", 14);
        double[] closes = series.closes();
        List<Double> rsi = new ArrayList<>(closes.length);

        double avgGain = 0, avgLoss = 0;
        for (int i = 0; i < closes.length; i++) {
            rsi.add(null);
            if (i == 0) continue;
            double delta = closes[i] - closes[i - 1];

            if (i < period) {
                avgGain += Math.max(delta, 0);
                avgLoss += Math.max(-delta, 0);
                continue;
            }
            if (i == period) {
                avgGain = (avgGain + Math.max(delta, 0)) / period;
                avgLoss = (avgLoss + Math.max(-delta, 0)) / period;
            } else {
                avgGain = (avgGain * (period - 1) + Math.max(delta, 0)) / period;
                avgLoss = (avgLoss * (period - 1) + Math.max(-delta, 0)) / period;
            }
            double rs = avgLoss == 0 ? 100 : avgGain / avgLoss;
            rsi.set(i, 100 - (100 / (1 + rs)));
        }
        var ts = series.candles().stream().map(c -> c.timestamp()).toList();
        return new IndicatorResult(name(), series.symbol(), Map.of("period", period), ts, Map.of("rsi", rsi));
    }
}
