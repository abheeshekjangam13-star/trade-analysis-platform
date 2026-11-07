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

    @org.junit.jupiter.api.Test
    void readsKiteCandleTimesWithOrWithoutAColonInTheOffset() {
        Instant expected = Instant.parse("2026-10-01T03:45:00Z");   // 09:15 IST
        org.assertj.core.api.Assertions.assertThat(KiteConnectClient.parseKiteTime("2026-10-01T09:15:00+0530")).isEqualTo(expected);
        org.assertj.core.api.Assertions.assertThat(KiteConnectClient.parseKiteTime("2026-10-01T09:15:00+05:30")).isEqualTo(expected);
        org.assertj.core.api.Assertions.assertThat(KiteConnectClient.parseKiteTime("2026-10-01 09:15:00")).isEqualTo(expected);
        org.assertj.core.api.Assertions.assertThat(KiteConnectClient.parseKiteTime("2026-10-01T03:45:00Z")).isEqualTo(expected);
    }
}
