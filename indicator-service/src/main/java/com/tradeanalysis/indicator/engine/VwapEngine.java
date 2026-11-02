package com.tradeanalysis.indicator.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.IndicatorResult;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class VwapEngine implements IndicatorEngine {

    @Override public String name() { return "VWAP"; }

    @Override
    public IndicatorResult compute(PriceSeries series, Params params) {
        List<Double> vwap = new ArrayList<>();
        double cumTPV = 0, cumVol = 0;
        for (Candle c : series.candles()) {
            double tp = (c.high().doubleValue() + c.low().doubleValue() + c.close().doubleValue()) / 3;
            cumTPV += tp * c.volume();
            cumVol += c.volume();
            vwap.add(cumVol == 0 ? null : cumTPV / cumVol);
        }
        var ts = series.candles().stream().map(c -> c.timestamp()).toList();
        return new IndicatorResult(name(), series.symbol(), Map.of(), ts, Map.of("vwap", vwap));
    }
}
