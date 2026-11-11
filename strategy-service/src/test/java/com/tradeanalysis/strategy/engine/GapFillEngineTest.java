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

class GapFillEngineTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate PREV = LocalDate.of(2026, 11, 2), DAY = LocalDate.of(2026, 11, 3);
    static final Instant NOW = LocalDateTime.of(2026, 11, 4, 10, 0).atZone(IST).toInstant();

    static Candle c(LocalDate d, int h, int m, double o, double hi, double lo, double cl) {
        return new Candle(LocalDateTime.of(d, LocalTime.of(h, m)).atZone(IST).toInstant(),
                BigDecimal.valueOf(o), BigDecimal.valueOf(hi), BigDecimal.valueOf(lo), BigDecimal.valueOf(cl), 0);
    }

    static Params params(String... extra) {
        Map<String, Object> m = new HashMap<>(Map.of("referenceMode", "GAP_FILL", "underlying", "BANKNIFTY", "gapPct", "0.2",
                "maxGapPct", "2", "gapFillTarget", "0.5"));
        for (int i = 0; i < extra.length; i += 2) m.put(extra[i], extra[i + 1]);
        return new Params(m);
    }

    static NiftyOpeningBreakoutEngine.BreakoutResult run(List<Candle> five, List<Candle> one, Params p) {
        return new GapFillEngine().run(new PriceSeries("NIFTY BANK", Interval.MINUTE_5, five), new PriceSeries("NIFTY BANK", Interval.MINUTE_1, one), p, 9L, NOW, null);
    }

    /** Yesterday closed 50,000; today opens 50,200 (+0.4%): the gap is 50,000-50,200, its middle 50,100. */
    static List<Candle> gapUpDay() {
        return new ArrayList<>(List.of(
                c(PREV, 15, 25, 50010, 50020, 49990, 50000),
                c(DAY, 9, 15, 50200, 50260, 50150, 50230),     // 09:15 candle: high 50,260 = the stop
                c(DAY, 9, 20, 50230, 50240, 50170, 50180)));   // closes 50,180, back below the 50,200 open: PE
    }

    @Test
    void gapUpDayTradesPeBackIntoTheGapToHalfTheFill() {
        var r = run(gapUpDay(), List.of(c(DAY, 9, 25, 50180, 50185, 50090, 50095)), params());   // low 50,090 <= 50,100
        NiftyTradeLog t = r.tradeLogs().get(0);
        assertThat(t.getOptionType()).isEqualTo("PE");
        assertThat(t.getEntrySpotPrice()).isEqualTo(50180.0);
        assertThat(t.getInitialStopLoss()).isEqualTo(50260.0);    // the 09:15 candle's high
        assertThat(t.getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(t.getExitSpotPrice()).isEqualTo(50100.0);      // halfway to yesterday's close
        assertThat(t.getLotSize()).isEqualTo(30);
    }

    @Test
    void fullFillTargetAndAStopCap() {
        var full = run(gapUpDay(), List.of(c(DAY, 9, 25, 50180, 50185, 49990, 50000)), params("gapFillTarget", "1")).tradeLogs().get(0);
        assertThat(full.getExitSpotPrice()).isEqualTo(50000.0);   // yesterday's close
        var capped = run(gapUpDay(), List.of(c(DAY, 9, 25, 50180, 50235, 50170, 50230)), params("maxStopPoints", "50")).tradeLogs().get(0);
        assertThat(capped.getInitialStopLoss()).isEqualTo(50230.0);   // entry + 50, closer than 50,260
        assertThat(capped.getExitReason()).isEqualTo("SL_HIT");
    }

    @Test
    void gapDownDayTradesCeAndSidesCanBeLimited() {
        List<Candle> five = new ArrayList<>(List.of(
                c(PREV, 15, 25, 50010, 50020, 49990, 50000),
                c(DAY, 9, 15, 49800, 49850, 49740, 49770),     // -0.4% gap; low 49,740 = the stop
                c(DAY, 9, 20, 49770, 49830, 49760, 49820)));   // closes back above the 49,800 open: CE
        List<Candle> one = List.of(c(DAY, 9, 25, 49820, 49905, 49815, 49900));   // high 49,905 >= middle 49,900
        NiftyTradeLog t = run(five, one, params()).tradeLogs().get(0);
        assertThat(t.getOptionType()).isEqualTo("CE");
        assertThat(t.getInitialStopLoss()).isEqualTo(49740.0);
        assertThat(t.getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(run(five, one, params("directions", "PE")).tradeLogs()).isEmpty();   // PE only: gap-up days only
    }

    @Test
    void noTradeWhenTheGapIsTooSmallOrAlreadyFilled() {
        List<Candle> small = new ArrayList<>(List.of(c(PREV, 15, 25, 50010, 50020, 49990, 50000),
                c(DAY, 9, 15, 50050, 50080, 50020, 50060), c(DAY, 9, 20, 50060, 50070, 50030, 50040)));   // +0.1%
        assertThat(run(small, List.of(), params()).days()).anyMatch(d -> d.date().equals(DAY) && d.status().equals("NO_TRADE"));
        List<Candle> filled = new ArrayList<>(List.of(c(PREV, 15, 25, 50010, 50020, 49990, 50000),
                c(DAY, 9, 15, 50200, 50210, 49980, 50050), c(DAY, 9, 20, 50050, 50060, 50020, 50030)));   // first candle reaches 50,000
        var r = run(filled, List.of(), params());
        assertThat(r.tradeLogs()).isEmpty();
        assertThat(r.days()).anyMatch(d -> d.date().equals(DAY) && d.reason().contains("filled in the first candle"));
    }

    @Test
    void liveKeepsAnOpenTradeAndParametersAreChecked() {
        Instant ten = LocalDateTime.of(DAY, LocalTime.of(10, 0)).atZone(IST).toInstant();
        var live = new NiftyOpeningBreakoutEngine().runLive(new PriceSeries("NIFTY BANK", Interval.MINUTE_5, gapUpDay()),
                new PriceSeries("NIFTY BANK", Interval.MINUTE_1, List.of(c(DAY, 9, 25, 50180, 50190, 50150, 50160))), params(), 9L, ten, DAY);
        assertThat(live.openTrades()).hasSize(1);
        assertThat(live.openTrades().get(0).getOptionType()).isEqualTo("PE");
        assertThatThrownBy(() -> GapFillEngine.validateParams(params("gapFillTarget", "1.5"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GapFillEngine.validateParams(params("directions", "WITH_GAP"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GapFillEngine.validateParams(params("maxGapPct", "0.1"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(GapFillEngine.handles(params())).isTrue();
    }
}
