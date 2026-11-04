package com.tradeanalysis.marketdata.client;

import com.tradeanalysis.common.dto.Interval;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class KiteConnectClientTest {

    @Test
    void intradayFromIsFlooredToIntervalBoundary() {
        // 11:15:58 IST — an unaligned `from` makes Kite return bars at :58 seconds.
        Instant from = Instant.parse("2026-11-03T05:45:58Z");
        assertThat(KiteConnectClient.alignDown(from, Interval.MINUTE_5)).isEqualTo(Instant.parse("2026-11-03T05:45:00Z"));
        assertThat(KiteConnectClient.alignDown(Instant.parse("2026-11-03T05:47:30Z"), Interval.MINUTE_5))
                .isEqualTo(Instant.parse("2026-11-03T05:45:00Z"));
    }

    @Test
    void dailyFromIsUnchanged() {
        Instant from = Instant.parse("2026-11-03T05:45:58Z");
        assertThat(KiteConnectClient.alignDown(from, Interval.DAY_1)).isEqualTo(from);
    }
}
