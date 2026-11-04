package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** Test helper: NIFTY candles keyed by IST bar start time. */
public final class Candles {
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final LocalDate DAY = LocalDate.of(2026, 10, 27);

    private Candles() {}

    public static Instant at(int h, int m) {
        return DAY.atTime(LocalTime.of(h, m)).atZone(IST).toInstant();
    }

    public static Candle bar(int h, int m, double o, double hi, double lo, double c) {
        return new Candle(at(h, m), BigDecimal.valueOf(o), BigDecimal.valueOf(hi),
                BigDecimal.valueOf(lo), BigDecimal.valueOf(c), 0);
    }

    public static Candle barOn(LocalDate day, int h, int m, double o, double hi, double lo, double c) {
        return new Candle(day.atTime(LocalTime.of(h, m)).atZone(IST).toInstant(), BigDecimal.valueOf(o),
                BigDecimal.valueOf(hi), BigDecimal.valueOf(lo), BigDecimal.valueOf(c), 0);
    }

    /** Close time of the 5-minute candle starting at h:m. */
    public static Instant closeOf(int h, int m) {
        return at(h, m).plusSeconds(300);
    }

    /** Close time of the 1-minute candle starting at h:m. */
    public static Instant close1m(int h, int m) {
        return at(h, m).plusSeconds(60);
    }
}
