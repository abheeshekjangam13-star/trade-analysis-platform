package com.tradeanalysis.strategy.live;

import com.tradeanalysis.common.dto.Candle;

import java.time.Instant;
import java.util.List;

/** Supplies NIFTY spot 1-minute candles for gap recovery (e.g. market-data-service range query). */
public interface CandleSource {

    /** 1-minute candles whose start time is in (afterExclusive, now], oldest first. */
    List<Candle> oneMinuteCandlesAfter(Instant afterExclusive, Instant now);
}
