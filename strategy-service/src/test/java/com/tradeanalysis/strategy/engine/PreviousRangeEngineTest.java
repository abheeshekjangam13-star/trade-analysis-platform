package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreviousRangeEngineTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate PREV = LocalDate.of(2026, 11, 2);   // Monday: yesterday's range 24000-24200, close 24100
    static final LocalDate DAY = LocalDate.of(2026, 11, 3);    // Tuesday
    static final Instant NOW = LocalDateTime.of(2026, 11, 4, 10, 0).atZone(IST).toInstant();

    static Candle c(LocalDate d, int h, int m, double o, double hi, double lo, double cl) {
        return new Candle(LocalDateTime.of(d, LocalTime.of(h, m)).atZone(IST).toInstant(),
                BigDecimal.valueOf(o), BigDecimal.valueOf(hi), BigDecimal.valueOf(lo), BigDecimal.valueOf(cl), 0);
    }

    static List<Candle> previousDay() {
        return List.of(c(PREV, 9, 15, 24050, 24200, 24000, 24100), c(PREV, 15, 25, 24100, 24150, 24080, 24100));
    }

    static Params params(Map<String, String> extra) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("referenceMode", "PREVIOUS_DAY");
        m.put("directions", "PE");
        m.put("entryUntil", "14:00");
        m.put("targetPoints", "30");
        m.putAll(extra);
        return new Params(m);
    }

    @Test
    void opensInsideYesterdaysRangeThenClosesBelowItsLowAndHitsTheTarget() {
        List<Candle> fiveMin = new java.util.ArrayList<>(previousDay());
        fiveMin.add(c(DAY, 9, 15, 24100, 24120, 24050, 24080));   // opens 24100: inside 24000-24200
        fiveMin.add(c(DAY, 9, 20, 24080, 24090, 24060, 24070));   // still above yesterday's low
        fiveMin.add(c(DAY, 9, 25, 24070, 24075, 23985, 23990));   // closes 23990, below 24000: entry
        fiveMin.add(c(DAY, 9, 30, 23990, 24000, 23960, 23970));
        List<Candle> oneMin = List.of(
                c(DAY, 9, 30, 23990, 23992, 23950, 23955));       // low 23950 <= 23990 - 30 = 23960: target
        PriceSeries series = new PriceSeries("NIFTY 50", Interval.MINUTE_5, fiveMin);
        PriceSeries minutes = new PriceSeries("NIFTY 50", Interval.MINUTE_1, oneMin);

        var result = new PreviousRangeEngine().run(series, minutes, params(Map.of()), 7L, NOW, null);

        assertThat(result.tradeLogs()).hasSize(1);
        NiftyTradeLog t = result.tradeLogs().get(0);
        assertThat(t.getOptionType()).isEqualTo("PE");
        assertThat(t.getEntrySpotPrice()).isEqualTo(23990.0);
        assertThat(t.getInitialStopLoss()).isEqualTo(24200.0);     // stop at yesterday's high
        assertThat(t.getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(t.getExitSpotPrice()).isEqualTo(23960.0);
        assertThat(result.days()).anyMatch(d -> d.date().equals(DAY) && d.status().equals("TRADED"));
    }

    @Test
    void aDayThatOpensOutsideYesterdaysRangeIsNotTraded() {
        List<Candle> fiveMin = new java.util.ArrayList<>(previousDay());
        fiveMin.add(c(DAY, 9, 15, 23900, 23950, 23850, 23880));   // opens 23900: below yesterday's low
        fiveMin.add(c(DAY, 9, 25, 23880, 23890, 23800, 23810));
        PriceSeries series = new PriceSeries("NIFTY 50", Interval.MINUTE_5, fiveMin);
        PriceSeries minutes = new PriceSeries("NIFTY 50", Interval.MINUTE_1, List.of());

        var result = new PreviousRangeEngine().run(series, minutes, params(Map.of()), 7L, NOW, null);

        assertThat(result.tradeLogs()).isEmpty();
        assertThat(result.days()).anyMatch(d -> d.date().equals(DAY) && d.status().equals("NO_TRADE")
                && d.reason().contains("outside yesterday's range"));
    }

    @Test
    void ceOpensInsideYesterdaysRangeThenClosesAboveItsHighAndHitsTheTarget() {
        List<Candle> fiveMin = new java.util.ArrayList<>(previousDay());
        fiveMin.add(c(DAY, 9, 15, 24100, 24150, 24090, 24140));   // opens 24100: inside 24000-24200
        fiveMin.add(c(DAY, 9, 20, 24140, 24190, 24130, 24180));   // still below yesterday's high
        fiveMin.add(c(DAY, 9, 25, 24180, 24230, 24175, 24220));   // closes 24220, above 24200: entry
        List<Candle> oneMin = List.of(
                c(DAY, 9, 30, 24220, 24255, 24215, 24250));       // high 24255 >= 24220 + 30: target
        var result = new PreviousRangeEngine().run(new PriceSeries("NIFTY 50", Interval.MINUTE_5, fiveMin),
                new PriceSeries("NIFTY 50", Interval.MINUTE_1, oneMin), params(Map.of("directions", "CE")), 7L, NOW, null);

        assertThat(result.tradeLogs()).hasSize(1);
        NiftyTradeLog t = result.tradeLogs().get(0);
        assertThat(t.getOptionType()).isEqualTo("CE");
        assertThat(t.getEntrySpotPrice()).isEqualTo(24220.0);
        assertThat(t.getInitialStopLoss()).isEqualTo(24000.0);     // stop at yesterday's low
        assertThat(t.getReferenceHigh()).isEqualTo(24200.0);       // the reference is yesterday's range, not today's 09:15 candle
        assertThat(t.getReferenceLow()).isEqualTo(24000.0);
        assertThat(t.getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(t.getExitSpotPrice()).isEqualTo(24250.0);
        assertThat(t.getEstimatedPnl()).isPositive();
    }

    @Test
    void ceStopIsYesterdaysLow() {
        List<Candle> fiveMin = new java.util.ArrayList<>(previousDay());
        fiveMin.add(c(DAY, 9, 15, 24100, 24150, 24090, 24140));
        fiveMin.add(c(DAY, 9, 20, 24140, 24230, 24130, 24210));   // entry at 24210
        List<Candle> oneMin = List.of(
                c(DAY, 9, 25, 24210, 24212, 24100, 24120),
                c(DAY, 9, 26, 24120, 24125, 23990, 23995));       // low 23990 <= 24000: stop
        var result = new PreviousRangeEngine().run(new PriceSeries("NIFTY 50", Interval.MINUTE_5, fiveMin),
                new PriceSeries("NIFTY 50", Interval.MINUTE_1, oneMin), params(Map.of("directions", "CE", "targetPoints", "0")), 7L, NOW, null);

        NiftyTradeLog t = result.tradeLogs().get(0);
        assertThat(t.getExitReason()).isEqualTo("SL_HIT");
        assertThat(t.getExitSpotPrice()).isEqualTo(24000.0);
    }

    @Test
    void bothTakesTheFirstBreakoutEitherWayOncePerDay() {
        List<Candle> fiveMin = new java.util.ArrayList<>(previousDay());
        fiveMin.add(c(DAY, 9, 15, 24100, 24120, 24050, 24080));
        fiveMin.add(c(DAY, 9, 20, 24080, 24085, 23980, 23990));   // below the low first: PE
        fiveMin.add(c(DAY, 9, 25, 23990, 24300, 23980, 24250));   // later above the high: ignored (one trade a day)
        var result = new PreviousRangeEngine().run(new PriceSeries("NIFTY 50", Interval.MINUTE_5, fiveMin),
                new PriceSeries("NIFTY 50", Interval.MINUTE_1, List.of()), params(Map.of("directions", "BOTH")), 7L, NOW, null);

        assertThat(result.tradeLogs()).hasSize(1);
        assertThat(result.tradeLogs().get(0).getOptionType()).isEqualTo("PE");
    }

    @Test
    void peOnlyIgnoresABreakoutAboveTheHigh() {
        List<Candle> fiveMin = new java.util.ArrayList<>(previousDay());
        fiveMin.add(c(DAY, 9, 15, 24100, 24150, 24090, 24140));
        fiveMin.add(c(DAY, 9, 20, 24140, 24230, 24130, 24220));   // above the high: not for PE
        var result = new PreviousRangeEngine().run(new PriceSeries("NIFTY 50", Interval.MINUTE_5, fiveMin),
                new PriceSeries("NIFTY 50", Interval.MINUTE_1, List.of()), params(Map.of()), 7L, NOW, null);
        assertThat(result.tradeLogs()).isEmpty();
    }

    @Test
    void liveKeepsATradeWithNoExitYetOpen() {
        List<Candle> fiveMin = new java.util.ArrayList<>(previousDay());
        fiveMin.add(c(DAY, 9, 15, 24100, 24150, 24090, 24140));
        fiveMin.add(c(DAY, 9, 20, 24140, 24230, 24130, 24220));   // CE entry at 24220
        List<Candle> oneMin = List.of(c(DAY, 9, 25, 24220, 24230, 24210, 24225));   // no stop, no target
        Instant tenAm = LocalDateTime.of(DAY, LocalTime.of(10, 0)).atZone(IST).toInstant();
        var live = new NiftyOpeningBreakoutEngine().runLive(new PriceSeries("NIFTY 50", Interval.MINUTE_5, fiveMin),
                new PriceSeries("NIFTY 50", Interval.MINUTE_1, oneMin), params(Map.of("directions", "CE")), 7L, tenAm, DAY);
        assertThat(live.openTrades()).hasSize(1);
        assertThat(live.openTrades().get(0).getOptionType()).isEqualTo("CE");
        assertThat(live.openTrades().get(0).getFinalStopLoss()).isEqualTo(24000.0);
        assertThat(live.tradeLogs()).isEmpty();
    }

    @Test
    void directionsCePeOrBoth() {
        PreviousRangeEngine.validateParams(params(Map.of("directions", "CE")));
        PreviousRangeEngine.validateParams(params(Map.of("directions", "BOTH")));
        PreviousRangeEngine.validateParams(params(Map.of("directions", "")));     // empty = PE, as before
        assertThatThrownBy(() -> PreviousRangeEngine.validateParams(params(Map.of("directions", "WITH_GAP"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PreviousRangeEngine.handles(params(Map.of()))).isTrue();
        assertThat(PreviousRangeEngine.handles(new Params(Map.of("referenceMode", "REFERENCE_CANDLE")))).isFalse();
    }
}
