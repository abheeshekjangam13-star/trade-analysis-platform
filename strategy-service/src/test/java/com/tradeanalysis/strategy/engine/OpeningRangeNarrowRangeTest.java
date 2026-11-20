package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The narrow-range day filter (narrowRangeDays / excludeNarrowRangeDays) on the opening-range (09:15 candle) mode. */
class OpeningRangeNarrowRangeTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    static final Instant NOW = LocalDateTime.of(2026, 11, 4, 10, 0).atZone(IST).toInstant();

    static Candle c(LocalDate d, int h, int m, double o, double hi, double lo, double cl) {
        return new Candle(LocalDateTime.of(d, LocalTime.of(h, m)).atZone(IST).toInstant(),
                BigDecimal.valueOf(o), BigDecimal.valueOf(hi), BigDecimal.valueOf(lo), BigDecimal.valueOf(cl), 0);
    }

    static Params params(String... extra) {
        Map<String, Object> m = new HashMap<>(Map.of("directions", "CE", "targetPoints", "20", "initialStop", "OPPOSITE"));
        for (int i = 0; i < extra.length; i += 2) m.put(extra[i], extra[i + 1]);
        return new Params(m);
    }

    /** Sessions before DAY with these high-low ranges (oldest first), centred on 24,100; then DAY breaks out above 24,110. */
    static NiftyOpeningBreakoutEngine.BreakoutResult run(Params p, double... ranges) {
        List<Candle> five = new ArrayList<>();
        for (int k = 0; k < ranges.length; k++) {
            LocalDate d = DAY.minusDays(ranges.length - k);
            five.add(c(d, 9, 15, 24100, 24100 + ranges[k] / 2, 24100 - ranges[k] / 2, 24100));
            five.add(c(d, 15, 25, 24100, 24100, 24100, 24100));
        }
        five.add(c(DAY, 9, 15, 24100, 24110, 24090, 24100));   // reference 24,090-24,110
        five.add(c(DAY, 9, 20, 24100, 24125, 24099, 24120));   // closes above 24,110: CE
        five.add(c(DAY, 9, 25, 24120, 24145, 24118, 24140));   // its 1-min bars carry the exit
        List<Candle> one = List.of(c(DAY, 9, 25, 24120, 24145, 24118, 24140));   // +20 target
        return new NiftyOpeningBreakoutEngine().run(new PriceSeries("NIFTY 50", Interval.MINUTE_5, five),
                new PriceSeries("NIFTY 50", Interval.MINUTE_1, one), p, 9L, NOW, DAY);
    }

    @Test
    void withoutTheSettingEveryDayTradesAsBefore() {
        assertThat(run(params(), 300, 80, 260, 240).tradeLogs()).hasSize(1);
        assertThat(run(params(), 300, 280, 260, 100).tradeLogs()).hasSize(1);
        assertThat(NiftyOpeningBreakoutEngine.warmupDays(params())).isEqualTo(7);
    }

    @Test
    void nr4TradesOnlyAfterAnNr4Day() {
        var nr4 = run(params("narrowRangeDays", "4"), 300, 280, 260, 100);
        assertThat(nr4.tradeLogs()).hasSize(1);
        assertThat(nr4.tradeLogs().get(0).getExitReason()).isEqualTo("TARGET_HIT");

        var notNr4 = run(params("narrowRangeDays", "4"), 300, 80, 260, 240);
        assertThat(notNr4.tradeLogs()).isEmpty();
        assertThat(notNr4.days()).anySatisfy(d -> {
            assertThat(d.date()).isEqualTo(DAY);
            assertThat(d.reason()).contains("not NR4");
        });
    }

    @Test
    void nr4NotNr7AndShortHistory() {
        var alsoNr7 = run(params("narrowRangeDays", "4", "excludeNarrowRangeDays", "7"), 300, 290, 280, 270, 260, 250, 100);
        assertThat(alsoNr7.tradeLogs()).isEmpty();
        assertThat(alsoNr7.days()).anySatisfy(d -> assertThat(d.reason()).contains("also an NR7"));
        assertThat(run(params("narrowRangeDays", "4", "excludeNarrowRangeDays", "7"), 300, 50, 280, 270, 260, 250, 100).tradeLogs()).hasSize(1);

        var shortHistory = run(params("narrowRangeDays", "4"), 300, 100);
        assertThat(shortHistory.tradeLogs()).isEmpty();
        assertThat(shortHistory.days()).anySatisfy(d -> assertThat(d.status()).isEqualTo("NO_DATA"));
    }

    @Test
    void settingsAreCheckedAndHistoryIsLoaded() {
        assertThatThrownBy(() -> NiftyOpeningBreakoutEngine.validateParams(params("narrowRangeDays", "1"))).hasMessageContaining("narrowRangeDays");
        assertThatThrownBy(() -> NiftyOpeningBreakoutEngine.validateParams(params("excludeNarrowRangeDays", "7"))).hasMessageContaining("needs narrowRangeDays");
        assertThat(NiftyOpeningBreakoutEngine.warmupDays(params("narrowRangeDays", "7"))).isGreaterThanOrEqualTo(20);
        // Gap-fill and EMA-cross strategies don't use it, so it loads no extra history for them.
        assertThat(NiftyOpeningBreakoutEngine.warmupDays(new Params(Map.of("referenceMode", "GAP_FILL", "narrowRangeDays", "7")))).isEqualTo(7);
    }
}
