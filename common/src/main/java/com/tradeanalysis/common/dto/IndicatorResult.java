package com.tradeanalysis.common.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Output of an indicator. Each named line (e.g. "macd", "signal") holds one value per
 * input candle; positions without enough history are null.
 */
public record IndicatorResult(
        String indicator,
        String symbol,
        Map<String, Object> parameters,
        List<Instant> timestamps,
        Map<String, List<Double>> lines) {
}
