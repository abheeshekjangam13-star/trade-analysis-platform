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

class CprEngineTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate PREV = LocalDate.of(2026, 11, 2), DAY = LocalDate.of(2026, 11, 3);
    static final Instant NOW = LocalDateTime.of(2026, 11, 4, 10, 0).atZone(IST).toInstant();

    static Candle c(LocalDate d, int h, int m, double o, double hi, double lo, double cl) {
        return new Candle(LocalDateTime.of(d, LocalTime.of(h, m)).atZone(IST).toInstant(),
                BigDecimal.valueOf(o), BigDecimal.valueOf(hi), BigDecimal.valueOf(lo), BigDecimal.valueOf(cl), 0);
    }

    static Params params(String... extra) {
        Map<String, Object> m = new HashMap<>(Map.of("referenceMode", "CPR", "targetPoints", "80"));
        for (int i = 0; i < extra.length; i += 2) m.put(extra[i], extra[i + 1]);
        return new Params(m);
    }

    /** Yesterday: high 24,200, low 24,000, close {@code close}. With close 24,100: P = BC = TC = 24,100, R1 24,200, S1 24,000. */
    static List<Candle> yesterday(double close) {
        return new ArrayList<>(List.of(c(PREV, 9, 15, 24100, 24200, 24000, 24100), c(PREV, 15, 25, 24100, 24110, close - 5, close)));
    }

    static NiftyOpeningBreakoutEngine.BreakoutResult run(List<Candle> five, List<Candle> one, Params p) {
        return new CprEngine().run(new PriceSeries("NIFTY 50", Interval.MINUTE_5, five), new PriceSeries("NIFTY 50", Interval.MINUTE_1, one), p, 3L, NOW, DAY);
    }

    @Test
    void closeAboveR1BuysCeWithTheStopAtThePivotCappedAndHitsTheTarget() {
        List<Candle> five = yesterday(24100);
        five.add(c(DAY, 9, 15, 24100, 24150, 24090, 24140));
        five.add(c(DAY, 9, 20, 24140, 24215, 24135, 24210));   // closes 24,210 above R1 24,200: CE
        var r = run(five, List.of(c(DAY, 9, 25, 24210, 24295, 24200, 24280)), params("maxStopPoints", "40", "maxCprWidthPct", "0.15"));
        NiftyTradeLog t = r.tradeLogs().get(0);
        assertThat(t.getOptionType()).isEqualTo("CE");
        assertThat(t.getEntrySpotPrice()).isEqualTo(24210.0);
        assertThat(t.getInitialStopLoss()).isEqualTo(24170.0);    // pivot 24,100, capped at entry − 40
        assertThat(t.getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(t.getExitSpotPrice()).isEqualTo(24290.0);
    }

    @Test
    void closeBelowS1BuysPeAndStopsAtThePivot() {
        List<Candle> five = yesterday(24100);
        five.add(c(DAY, 9, 15, 24100, 24110, 24020, 24030));
        five.add(c(DAY, 9, 20, 24030, 24035, 23985, 23990));   // closes 23,990 below S1 24,000: PE
        var r = run(five, List.of(c(DAY, 9, 25, 23990, 24100, 23980, 24090)), params());
        NiftyTradeLog t = r.tradeLogs().get(0);
        assertThat(t.getOptionType()).isEqualTo("PE");
        assertThat(t.getInitialStopLoss()).isEqualTo(24100.0);    // no cap: the pivot
        assertThat(t.getExitReason()).isEqualTo("SL_HIT");
        assertThat(t.getExitSpotPrice()).isEqualTo(24100.0);
    }

    @Test
    void wideCprAndAnOpenOutsideS1R1AreSkipped() {
        // Close 24,180: P 24,126.67, BC 24,100, TC 24,153.33: width 0.22% of P, above 0.15%.
        List<Candle> wide = yesterday(24180);
        wide.add(c(DAY, 9, 15, 24150, 24160, 24140, 24150));
        wide.add(c(DAY, 9, 20, 24150, 24300, 24145, 24290));
        var r = run(wide, List.of(), params("maxCprWidthPct", "0.15"));
        assertThat(r.tradeLogs()).isEmpty();
        assertThat(r.days()).anySatisfy(d -> assertThat(d.reason()).contains("CPR width"));

        List<Candle> outside = yesterday(24100);
        outside.add(c(DAY, 9, 15, 24250, 24260, 24240, 24255));   // opens 24,250 above R1
        outside.add(c(DAY, 9, 20, 24255, 24300, 24250, 24290));
        var o = run(outside, List.of(), params());
        assertThat(o.tradeLogs()).isEmpty();
        assertThat(o.days()).anySatisfy(d -> assertThat(d.reason()).contains("outside S1-R1"));
    }

    @Test
    void validatesItsParameters() {
        assertThat(CprEngine.handles(params())).isTrue();
        assertThatThrownBy(() -> CprEngine.validateParams(params("maxCprWidthPct", "-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CprEngine.validateParams(params("directions", "WITH_GAP"))).isInstanceOf(IllegalArgumentException.class);
        // Checked wherever the opening-breakout parameters are checked (saving, the description reader, the live runner).
        assertThatThrownBy(() -> NiftyOpeningBreakoutEngine.validateParams(params("maxCprWidthPct", "9"))).isInstanceOf(IllegalArgumentException.class);
    }
}
