package com.tradeanalysis.indicator.engine;

import com.tradeanalysis.common.dto.IndicatorResult;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MacdEngine implements IndicatorEngine {

    private final EmaEngine ema;

    @Autowired
    public MacdEngine(EmaEngine ema) { this.ema = ema; }

    @Override public String name() { return "MACD"; }

    @Override
    public IndicatorResult compute(PriceSeries series, Params params) {
        int fast   = params.intOr("fast", 12);
        int slow   = params.intOr("slow", 26);
        int signal = params.intOr("signal", 9);

        List<Double> fastEma  = ema.compute(series, new com.tradeanalysis.common.indicator.Params(Map.of("period", fast))).lines().get("ema");
        List<Double> slowEma  = ema.compute(series, new com.tradeanalysis.common.indicator.Params(Map.of("period", slow))).lines().get("ema");

        List<Double> macdLine = new ArrayList<>();
        for (int i = 0; i < fastEma.size(); i++) {
            Double f = fastEma.get(i), s = slowEma.get(i);
            macdLine.add((f != null && s != null) ? f - s : null);
        }

        // signal = 9-period EMA of macd (simple scan, not recursive for brevity)
        double k = 2.0 / (signal + 1);
        List<Double> signalLine = new ArrayList<>();
        Double prev = null; int count = 0;
        for (int i = 0; i < macdLine.size(); i++) {
            Double m = macdLine.get(i);
            if (m == null) { signalLine.add(null); continue; }
            if (prev == null) {
                count++;
                if (count < signal) { signalLine.add(null); prev = m; continue; }
                prev = m;
                signalLine.add(prev);
            } else {
                prev = m * k + prev * (1 - k);
                signalLine.add(prev);
            }
        }

        List<Double> hist = new ArrayList<>();
        for (int i = 0; i < macdLine.size(); i++) {
            Double m = macdLine.get(i), s = signalLine.get(i);
            hist.add((m != null && s != null) ? m - s : null);
        }

        var ts = series.candles().stream().map(c -> c.timestamp()).toList();
        return new IndicatorResult(name(), series.symbol(),
                Map.of("fast", fast, "slow", slow, "signal", signal),
                ts, Map.of("macd", macdLine, "signal", signalLine, "histogram", hist));
    }
}
