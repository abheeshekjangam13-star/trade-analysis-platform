package com.tradeanalysis.common.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One OHLCV bar. */
public record Candle(
        Instant timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume) {
}
