package com.tradeanalysis.indicator.engine;

import com.tradeanalysis.common.dto.IndicatorResult;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class EmaEngine implements IndicatorEngine {

    @Override public String name() { return "EMA"; }

    @Override
    public IndicatorResult compute(PriceSeries series, Params params) {
        int period = params.intOr("period", 20);
        double[] closes = series.closes();
        double k = 2.0 / (period + 1);
        List<Double> ema = new ArrayList<>(closes.length);

        Double prev = null;
        for (int i = 0; i < closes.length; i++) {
            if (i < period - 1) { ema.add(null); continue; }
            if (prev == null) {
                double sum = 0;
                for (int j = 0; j < period; j++) sum += closes[j];
                prev = sum / period;
                ema.add(prev);
                continue;
            }
            prev = closes[i] * k + prev * (1 - k);
            ema.add(prev);
        }
        var ts = series.candles().stream().map(c -> c.timestamp()).toList();
        return new IndicatorResult(name(), series.symbol(), Map.of("period", period), ts, Map.of("ema", ema));
    }
}
