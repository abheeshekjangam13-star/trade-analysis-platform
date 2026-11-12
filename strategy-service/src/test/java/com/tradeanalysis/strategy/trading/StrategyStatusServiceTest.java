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
    void previousDayTriggersAreYesterdaysHighAndLow() {
        var t = StrategyStatusService.triggers(p("referenceMode", "PREVIOUS_DAY"), bars, TODAY, 25950.0);
        assertThat(t).containsExactly(new StrategyStatusService.Trigger("CE", 26000, 50), new StrategyStatusService.Trigger("PE", 25800, 150));
    }

    @Test
    void cprTriggersAreR1AndS1AndFollowTheDirections() {
        // P = (26000 + 25800 + 25900) / 3 = 25900; R1 = 2P − L = 26000; S1 = 2P − H = 25800
        var t = StrategyStatusService.triggers(p("referenceMode", "CPR", "directions", "CE"), bars, TODAY, 25950.0);
        assertThat(t).containsExactly(new StrategyStatusService.Trigger("CE", 26000, 50));
    }

    @Test
    void openingRangeTriggersWaitForTheRangeToForm() {
        assertThat(StrategyStatusService.triggers(p(), bars, TODAY, 25950.0))
                .containsExactly(new StrategyStatusService.Trigger("CE", 25920, -30), new StrategyStatusService.Trigger("PE", 25880, 70));
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
