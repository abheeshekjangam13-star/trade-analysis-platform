package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.indicator.Params;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyStatusServiceTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate YDAY = LocalDate.of(2026, 11, 11), TODAY = LocalDate.of(2026, 11, 12);

    static Candle bar(LocalDate d, int h, int m, double o, double hi, double lo, double c) {
        return new Candle(d.atTime(h, m).atZone(IST).toInstant(), BigDecimal.valueOf(o), BigDecimal.valueOf(hi),
                BigDecimal.valueOf(lo), BigDecimal.valueOf(c), 0);
    }

    static Params p(String... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new Params(m);
    }

    // Yesterday: high 26,000, low 25,800, close 25,900. Today: 09:15 candle 25,920-25,880, then 25,950.
    final List<Candle> bars = List.of(
            bar(YDAY, 9, 15, 25850, 26000, 25800, 25950), bar(YDAY, 15, 25, 25950, 25960, 25890, 25900),
            bar(TODAY, 9, 15, 25900, 25920, 25880, 25910), bar(TODAY, 9, 20, 25910, 25955, 25905, 25950));

    @Test
    void cachedStatusExpiresAtEachFiveMinuteCloseOrAfterAMinute() {
        Instant at = TODAY.atTime(10, 1, 10).atZone(IST).toInstant();
        assertThat(StrategyStatusService.fresh(at, at.plusSeconds(50))).isTrue();                          // 10:02:00: same candle
        assertThat(StrategyStatusService.fresh(at, at.plusSeconds(61))).isFalse();                         // a minute old
        Instant beforeClose = TODAY.atTime(10, 4, 40).atZone(IST).toInstant();
        assertThat(StrategyStatusService.fresh(beforeClose, beforeClose.plusSeconds(22))).isTrue();        // 10:05:02: within the grace
        assertThat(StrategyStatusService.fresh(beforeClose, beforeClose.plusSeconds(26))).isFalse();       // 10:05:06: the 10:00 candle closed
    }

    @Test
    void triggersCarryTheStopAndTargetTheTradeWouldStartWith() {
        // Previous day: stop on the other side of yesterday's range, pulled to 100 points by the cap; target 50 points.
        var t = StrategyStatusService.triggers(p("referenceMode", "PREVIOUS_DAY", "maxStopPoints", "100", "targetPoints", "50"), bars, TODAY, 25950.0);
        assertThat(t).containsExactly(new StrategyStatusService.Trigger("CE", 26000, 50, 25900.0, 26050.0),
                new StrategyStatusService.Trigger("PE", 25800, 150, 25900.0, 25750.0));
        // Opening range (09:15 candle 25,920-25,880): midpoint stop; 2R target.
        var o = StrategyStatusService.triggers(p("targetR", "2"), bars, TODAY, 25950.0);
        assertThat(o.get(0)).isEqualTo(new StrategyStatusService.Trigger("CE", 25920, -30, 25900.0, 25960.0));
        // Breakout-candle stop without a cap: not known until the candle; no stop, no R target.
        var b = StrategyStatusService.triggers(p("initialStop", "BREAKOUT_CANDLE", "targetR", "2"), bars, TODAY, 25950.0);
        assertThat(b.get(0).stop()).isNull();
        assertThat(b.get(0).target()).isNull();
        // CPR: stop at the pivot (26000 + 25800 + 25900) / 3 = 25,900.
        var c = StrategyStatusService.triggers(p("referenceMode", "CPR"), bars, TODAY, 25950.0);
        assertThat(c.get(0).stop()).isEqualTo(25900.0);
    }

    @Test
    void previousDayTriggersAreYesterdaysHighAndLow() {
        var t = StrategyStatusService.triggers(p("referenceMode", "PREVIOUS_DAY"), bars, TODAY, 25950.0);
        assertThat(t).containsExactly(new StrategyStatusService.Trigger("CE", 26000, 50, 25800.0, null), new StrategyStatusService.Trigger("PE", 25800, 150, 26000.0, null));   // stop: the other side
    }

    @Test
    void cprTriggersAreR1AndS1AndFollowTheDirections() {
        // P = (26000 + 25800 + 25900) / 3 = 25900; R1 = 2P − L = 26000; S1 = 2P − H = 25800
        var t = StrategyStatusService.triggers(p("referenceMode", "CPR", "directions", "CE"), bars, TODAY, 25950.0);
        assertThat(t).containsExactly(new StrategyStatusService.Trigger("CE", 26000, 50, 25900.0, null));   // stop: the pivot
    }

    @Test
    void openingRangeTriggersWaitForTheRangeToForm() {
        assertThat(StrategyStatusService.triggers(p(), bars, TODAY, 25950.0))
                .containsExactly(new StrategyStatusService.Trigger("CE", 25920, -30, 25900.0, null), new StrategyStatusService.Trigger("PE", 25880, 70, 25900.0, null));   // stop: the midpoint
        assertThat(StrategyStatusService.triggers(p("referenceMinutes", "15"), bars, TODAY, 25950.0)).isEmpty();   // 09:15-09:30 not complete
    }

    @Test
    void emaCrossAndGapFillHaveNoFixedLevel() {
        assertThat(StrategyStatusService.triggers(p("referenceMode", "EMA_CROSS"), bars, TODAY, 25950.0)).isEmpty();
        assertThat(StrategyStatusService.triggers(p("referenceMode", "GAP_FILL"), bars, TODAY, 25950.0)).isEmpty();
    }

    @Test
    void exitReasonsReadAsSentences() {
        assertThat(StrategyStatusService.readable("TARGET_HIT")).isEqualTo("Target hit");
        assertThat(StrategyStatusService.readable("STOPPED_BY_USER")).isEqualTo("Closed by you");
    }
}
