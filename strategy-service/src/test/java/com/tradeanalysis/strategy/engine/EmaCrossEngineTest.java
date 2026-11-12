package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmaCrossEngineTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate PREV = LocalDate.of(2026, 11, 2), DAY = LocalDate.of(2026, 11, 3);
    static final Instant NOW = LocalDateTime.of(2026, 11, 4, 10, 0).atZone(IST).toInstant();

    static Candle c(LocalDate d, int h, int m, double o, double hi, double lo, double cl) {
        return new Candle(LocalDateTime.of(d, LocalTime.of(h, m)).atZone(IST).toInstant(),
                BigDecimal.valueOf(o), BigDecimal.valueOf(hi), BigDecimal.valueOf(lo), BigDecimal.valueOf(cl), 0);
    }

    /** EMA 2/3 keeps the arithmetic small; the rules are the same as for 9/21. */
    static Params params(String... extra) {
        Map<String, Object> m = new HashMap<>(Map.of("referenceMode", "EMA_CROSS", "fastEma", "2", "slowEma", "3", "targetPoints", "4"));
        for (int i = 0; i < extra.length; i += 2) m.put(extra[i], extra[i + 1]);
        return new Params(m);
    }

    /** Flat at 100 yesterday, a dip to 98 from 09:15 (fast EMA under slow), then a jump to 110 on the {@code jumpAt} candle. */
    static List<Candle> bars(LocalTime jumpAt) {
        List<Candle> out = new ArrayList<>();
        for (int m = 0; m < 3; m++) out.add(c(PREV, 15, 10 + 5 * m, 100, 100, 100, 100));
        for (LocalTime t = LocalTime.of(9, 15); t.isBefore(LocalTime.of(10, 0)); t = t.plusMinutes(5)) {
            boolean jump = t.equals(jumpAt), after = t.isAfter(jumpAt);
            double close = after ? 110 : jump ? 110 : t.equals(LocalTime.of(9, 15)) ? 99 : 98;
            double low = t.equals(LocalTime.of(9, 35)) ? 97.5 : Math.min(close, 97.9);
            out.add(c(DAY, t.getHour(), t.getMinute(), 98, Math.max(close, 98.5), low, close));
        }
        return out;
    }

    static NiftyOpeningBreakoutEngine.BreakoutResult run(List<Candle> five, List<Candle> one, Params p) {
        return new EmaCrossEngine().run(new PriceSeries("NIFTY 50", Interval.MINUTE_5, five), new PriceSeries("NIFTY 50", Interval.MINUTE_1, one), p, 5L, NOW, DAY);
    }

    @Test
    void fastEmaCrossingAboveTheSlowOneAfter0945BuysCeWithASwingStop() {
        var r = run(bars(LocalTime.of(9, 45)), List.of(c(DAY, 9, 50, 110, 114.5, 109, 114)), params());
        NiftyTradeLog t = r.tradeLogs().get(0);
        assertThat(t.getOptionType()).isEqualTo("CE");
        assertThat(t.getEntrySpotPrice()).isEqualTo(110.0);
        assertThat(t.getInitialStopLoss()).isEqualTo(97.5);       // lowest low of 09:35, 09:40, 09:45
        assertThat(t.getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(t.getExitSpotPrice()).isEqualTo(114.0);
    }

    @Test
    void stopCapAndSwingLength() {
        var capped = run(bars(LocalTime.of(9, 45)), List.of(), params("maxStopPoints", "5"));
        assertThat(capped.tradeLogs().get(0).getInitialStopLoss()).isEqualTo(105.0);   // 97.5 is more than 5 below 110
        var oneBar = run(bars(LocalTime.of(9, 45)), List.of(), params("swingBars", "1"));
        assertThat(oneBar.tradeLogs().get(0).getInitialStopLoss()).isEqualTo(97.9);     // the entry candle's own low
    }

    @Test
    void aCrossBeforeEntryFromIsNotTaken() {
        var r = run(bars(LocalTime.of(9, 30)), List.of(), params());
        assertThat(r.tradeLogs()).isEmpty();
        assertThat(r.days()).anySatisfy(d -> assertThat(d.reason()).contains("No EMA 2/3 crossover"));
        // With an earlier start the same cross trades.
        assertThat(run(bars(LocalTime.of(9, 30)), List.of(), params("entryFrom", "09:20")).tradeLogs()).hasSize(1);
        // A PE-only strategy ignores the upward cross.
        assertThat(run(bars(LocalTime.of(9, 45)), List.of(), params("directions", "PE")).tradeLogs()).isEmpty();
    }

    @Test
    void validatesItsParameters() {
        assertThat(EmaCrossEngine.handles(params())).isTrue();
        assertThatThrownBy(() -> EmaCrossEngine.validateParams(params("slowEma", "2"))).hasMessageContaining("slowEma");
        assertThatThrownBy(() -> EmaCrossEngine.validateParams(params("swingBars", "0"))).hasMessageContaining("swingBars");
        assertThatThrownBy(() -> EmaCrossEngine.validateParams(params("entryFrom", "14:30"))).hasMessageContaining("entryFrom");
        assertThat(EmaCrossEngine.ema(bars(LocalTime.of(9, 45)), 2)[0]).isEqualTo(100.0);   // seeded with the first close
    }
}
