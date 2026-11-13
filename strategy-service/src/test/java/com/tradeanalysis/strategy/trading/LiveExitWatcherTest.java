package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.indicator.Params;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiveExitWatcherTest {

    @Test
    void aCallExitsAtOrBelowTheStopAndAtOrAboveTheTarget() {
        assertThat(LiveExitWatcher.touched("CE", 24116, 24115.0, 24270.0)).isNull();
        assertThat(LiveExitWatcher.touched("CE", 24115, 24115.0, 24270.0)).isEqualTo("SL_HIT");
        assertThat(LiveExitWatcher.touched("CE", 24270, 24115.0, 24270.0)).isEqualTo("TARGET_HIT");
        assertThat(LiveExitWatcher.touched("CE", 24300, null, null)).isNull();
    }

    @Test
    void aPutIsTheOtherWayRound() {
        assertThat(LiveExitWatcher.touched("PE", 24200, 24250.0, 24100.0)).isNull();
        assertThat(LiveExitWatcher.touched("PE", 24250, 24250.0, 24100.0)).isEqualTo("SL_HIT");
        assertThat(LiveExitWatcher.touched("PE", 24099, 24250.0, 24100.0)).isEqualTo("TARGET_HIT");
    }

    @Test
    void theStopComesFirstWhenBothAreReached() {   // a stop above the target can only follow a trailed stop on a PE
        assertThat(LiveExitWatcher.touched("PE", 24300, 24250.0, 24400.0)).isEqualTo("SL_HIT");
    }

    static Params p(String... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new Params(m);
    }

    @Test
    void theTargetIsEntryPlusOrMinusTargetPointsWhereThatIsTheRule() {
        LivePosition ce = new LivePosition(1L, 1L, LocalDate.of(2026, 11, 3), TradingMode.PAPER, "CE", "X", 65, Instant.EPOCH, 24190, 24115.0);
        LivePosition pe = new LivePosition(1L, 1L, LocalDate.of(2026, 11, 3), TradingMode.PAPER, "PE", "X", 65, Instant.EPOCH, 24190, 24250.0);
        assertThat(LiveExitWatcher.target(ce, p("targetPoints", "80"))).isEqualTo(24270.0);
        assertThat(LiveExitWatcher.target(pe, p("referenceMode", "PREVIOUS_DAY", "targetPoints", "50"))).isEqualTo(24140.0);
        assertThat(LiveExitWatcher.target(ce, p())).isNull();                                                  // no target
        assertThat(LiveExitWatcher.target(ce, p("targetR", "2"))).isNull();                                    // R target: minute run
        assertThat(LiveExitWatcher.target(ce, p("referenceMode", "GAP_FILL", "targetPoints", "50"))).isNull();   // gap fill: its own target
    }
}
