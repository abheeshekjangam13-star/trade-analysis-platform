package com.tradeanalysis.common.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record Signal(Instant timestamp, SignalType type, BigDecimal price, String reason) {

    public enum SignalType { BUY, SELL, HOLD }
}
