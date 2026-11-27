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
    void breakevenMovesTheStopToEntryPlusTheLockOnceThePriceIsTrailAfterPointsInFavour() {
        // #183 on 25 Nov: CE from 25,774.1, breakeven after +40 locking 10 → stop 25,784.1 once Nifty reaches 25,814.1.
        LivePosition ce = new LivePosition(183L, 7L, LocalDate.of(2026, 11, 25), TradingMode.LIVE, "CE", "X", 260, Instant.EPOCH, 25774.1, 25674.1);
        Params be = p("trailAfterPoints", "40", "breakevenOnTrail", "true", "breakevenLockPoints", "10");
        assertThat(LiveExitWatcher.breakevenStop(ce, be, 25814.0)).isNull();
        assertThat(LiveExitWatcher.breakevenStop(ce, be, 25817.7)).isCloseTo(25784.1, org.assertj.core.data.Offset.offset(1e-9));
        LivePosition pe = new LivePosition(1L, 1L, LocalDate.of(2026, 11, 25), TradingMode.PAPER, "PE", "X", 65, Instant.EPOCH, 25000, 25060.0);
        assertThat(LiveExitWatcher.breakevenStop(pe, p("trailAfterPoints", "20", "breakevenOnTrail", "true"), 24980)).isEqualTo(25000.0);
        assertThat(LiveExitWatcher.breakevenStop(ce, p("trailAfterPoints", "40"), 25900)).isNull();                        // no breakeven
        assertThat(LiveExitWatcher.breakevenStop(ce, p("trailAfterR", "1", "breakevenOnTrail", "true"), 25900)).isNull();   // R: minute run
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

    @Test
    void aTargetFromTheLineIsTheOneSavedWhenThePositionOpened() {
        // #174: line 25,975, bought 25,995, target 60 from the line = 26,035 (not 25,995 + 60).
        LivePosition ce = new LivePosition(174L, 1L, LocalDate.of(2026, 11, 27), TradingMode.LIVE, "CE", "X", 260, Instant.EPOCH, 25995, 25895.0);
        ce.targetAt(26035.0);
        assertThat(LiveExitWatcher.target(ce, p("referenceMode", "PREVIOUS_DAY", "targetPoints", "60", "targetFrom", "LINE"))).isEqualTo(26035.0);
    }

    @Test
    void theRunnerSavesTheTargetFromTheLineTheEngineTradeBroke() {
        var t = new com.tradeanalysis.strategy.model.NiftyTradeLog(174L, "NIFTY 50", LocalDate.of(2026, 11, 27), 1, "BULLISH", 25975, 25700,
                Instant.EPOCH, Instant.EPOCH, 25995, "CE", 26000, 4, 65, 25895.0);
        assertThat(LiveStrategyRunner.lineTarget(p("targetPoints", "60", "targetFrom", "LINE"), t)).isEqualTo(26035.0);
        assertThat(LiveStrategyRunner.lineTarget(p("targetPoints", "60"), t)).isNull();                 // from the entry: the watcher's rule
        var pe = new com.tradeanalysis.strategy.model.NiftyTradeLog(243L, "NIFTY 50", LocalDate.of(2026, 11, 27), 1, "BEARISH", 25995, 25754,
                Instant.EPOCH, Instant.EPOCH, 25731, "PE", 25700, 2, 65, 25791.0);
        assertThat(LiveStrategyRunner.lineTarget(p("targetPoints", "100", "targetFrom", "LINE"), pe)).isEqualTo(25654.0);
    }
}
