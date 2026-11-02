package com.tradeanalysis.indicator.engine;

import com.tradeanalysis.common.dto.IndicatorResult;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class BollingerBandsEngine implements IndicatorEngine {

    @Override public String name() { return "BBANDS"; }

    @Override
    public IndicatorResult compute(PriceSeries series, Params params) {
        int period = params.intOr("period", 20);
        double mult = params.doubleOr("mult", 2.0);
        double[] closes = series.closes();

        List<Double> upper = new ArrayList<>(), mid = new ArrayList<>(), lower = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            if (i < period - 1) { upper.add(null); mid.add(null); lower.add(null); continue; }
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) sum += closes[j];
            double avg = sum / period;
            double var = 0;
            for (int j = i - period + 1; j <= i; j++) var += Math.pow(closes[j] - avg, 2);
            double std = Math.sqrt(var / period);
            mid.add(avg); upper.add(avg + mult * std); lower.add(avg - mult * std);
        }
        var ts = series.candles().stream().map(c -> c.timestamp()).toList();
        return new IndicatorResult(name(), series.symbol(),
                Map.of("period", period, "mult", mult),
                ts, Map.of("upper", upper, "mid", mid, "lower", lower));
    }
}
