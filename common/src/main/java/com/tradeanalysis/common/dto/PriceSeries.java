package com.tradeanalysis.common.dto;

import java.util.List;

/** Ordered (oldest first) series of candles for one symbol. */
public record PriceSeries(String symbol, Interval interval, List<Candle> candles) {

    public double[] closes() {
        return candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
    }
}
