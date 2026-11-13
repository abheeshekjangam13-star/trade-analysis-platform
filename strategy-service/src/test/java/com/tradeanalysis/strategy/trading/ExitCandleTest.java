package com.tradeanalysis.strategy.trading;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExitCandleTest {

    @Test
    void aCandleRangeTouchesTheStopFirstThenTheTarget() {
        // CE: stop 100 below, target 200 above
        assertThat(LiveExitWatcher.touchedRange("CE", 150, 100, 100.0, 200.0)).isEqualTo("SL_HIT");
        assertThat(LiveExitWatcher.touchedRange("CE", 200, 101, 100.0, 200.0)).isEqualTo("TARGET_HIT");
        assertThat(LiveExitWatcher.touchedRange("CE", 210, 90, 100.0, 200.0)).isEqualTo("SL_HIT");     // both: stop first
        assertThat(LiveExitWatcher.touchedRange("CE", 199, 101, 100.0, 200.0)).isNull();
        // PE: stop 200 above, target 100 below
        assertThat(LiveExitWatcher.touchedRange("PE", 200, 150, 200.0, 100.0)).isEqualTo("SL_HIT");
        assertThat(LiveExitWatcher.touchedRange("PE", 199, 100, 200.0, 100.0)).isEqualTo("TARGET_HIT");
        assertThat(LiveExitWatcher.touchedRange("PE", 199, 101, 200.0, null)).isNull();
    }

    @Test
    void kiteMinuteCandlesAreRead() {
        List<KiteApi.MinuteBar> bars = KiteRestApi.parseMinuteBars(List.of(
                List.of("2026-11-13T10:58:00+0530", 25210.1, 25213.05, 25202.35, 25205.0, 0),
                List.of("2026-11-13T10:59:00+0530", 25205.0, 25209, 25199.5, 25200.2, 0)));
        assertThat(bars).containsExactly(
                new KiteApi.MinuteBar(Instant.parse("2026-11-13T05:28:00Z"), 25213.05, 25202.35),
                new KiteApi.MinuteBar(Instant.parse("2026-11-13T05:29:00Z"), 25209, 25199.5));
        assertThat(KiteRestApi.INDEX_TOKENS).containsEntry("NIFTY 50", 256265L).containsEntry("NIFTY BANK", 260105L);
    }
}
