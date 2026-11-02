package com.tradeanalysis.common.dto;

import java.time.Duration;

public enum Interval {
    MINUTE_1(Duration.ofMinutes(1)),
    MINUTE_5(Duration.ofMinutes(5)),
    MINUTE_15(Duration.ofMinutes(15)),
    HOUR_1(Duration.ofHours(1)),
    DAY_1(Duration.ofDays(1)),
    WEEK_1(Duration.ofDays(7));

    private final Duration duration;

    Interval(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }
}
