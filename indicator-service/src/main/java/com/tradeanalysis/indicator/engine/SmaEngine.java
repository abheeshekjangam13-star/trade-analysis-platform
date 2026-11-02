package com.tradeanalysis.indicator.engine;

import com.tradeanalysis.common.dto.IndicatorResult;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SmaEngine implements IndicatorEngine {

    @Override public String name() { return "SMA"; }

    @Override
    public IndicatorResult compute(PriceSeries series, Params params) {
        int period = params.intOr("period", 20);
        double[] closes = series.closes();
        List<Double> sma = new ArrayList<>(closes.length);

        for (int i = 0; i < closes.length; i++) {
            if (i < period - 1) { sma.add(null); continue; }
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) sum += closes[j];
            sma.add(sum / period);
        }
        return result(series, Map.of("period", period), Map.of("sma", sma));
    }

    protected IndicatorResult result(PriceSeries s, Map<String, Object> params, Map<String, List<Double>> lines) {
        var timestamps = s.candles().stream().map(c -> c.timestamp()).toList();
        return new IndicatorResult(name(), s.symbol(), params, timestamps, lines);
    }
}
