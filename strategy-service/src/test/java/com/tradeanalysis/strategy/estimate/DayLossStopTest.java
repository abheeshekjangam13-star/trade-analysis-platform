package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** The max day loss in backtests: closed + open P&L checked after every minute, as live trading does. */
class DayLossStopTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 8, 7);

    static Instant at(int h, int m) { return DAY.atTime(h, m).atZone(IST).toInstant(); }

    /** NIFTY falls one point a minute from 25000 at 09:30 (flat before). */
    static Map<String, PriceSeries> fallingNifty() {
        List<Candle> out = new ArrayList<>();
        for (Instant t = at(9, 15); t.isBefore(at(15, 30)); t = t.plusSeconds(60)) {
            long k = Math.max(0, Duration.between(at(9, 30), t).toMinutes());
            BigDecimal p = BigDecimal.valueOf(25000 - k);
            out.add(new Candle(t, p, p, p, p, 0));
        }
        return Map.of("NIFTY 50", new PriceSeries("NIFTY 50", Interval.MINUTE_1, out));
    }

    /** A 2-lot NIFTY trade (65 per lot: 65 rupees a point), no charges, closed as given. */
    static NiftyTradeLog trade(String side, Instant entry, double entrySpot, Instant exit, double exitSpot, String reason) {
        NiftyTradeLog t = new NiftyTradeLog(7L, "NIFTY 50", DAY, 1, "BULLISH", 25010, 24990, at(9, 15), entry, entrySpot,
                side, 25000, 2, 65, entrySpot - 100);
        t.setContext(0.1, 0);
        t.close(exit, exitSpot, reason, entrySpot - 100);
        return t;
    }

    @Test
    void onReachingTheLimitOpenTradesAreClosedAndLaterEntriesNotTaken() {
        NiftyTradeLog a = trade("CE", at(9, 30), 25000, at(11, 0), 24910, "SL_HIT");
        NiftyTradeLog b = trade("CE", at(9, 30), 25000, at(11, 0), 24910, "SL_HIT");
        NiftyTradeLog later = trade("CE", at(10, 30), 24970, at(12, 0), 25100, "TARGET_HIT");

        DayLossStop.Result r = DayLossStop.apply(List.of(a, b, later), 5000.0, fallingNifty());

        // Two trades lose 130 a point together: −5000 is passed 39 points down, the 10:09 candle (closes 10:10).
        assertThat(r.stops()).hasSize(1);
        DayLossStop.Stop stop = r.stops().get(0);
        assertThat(stop.at()).isEqualTo(at(10, 10));
        assertThat(stop.closed()).isEqualTo(2);
        assertThat(stop.blocked()).isEqualTo(1);
        for (NiftyTradeLog t : List.of(a, b)) {
            assertThat(t.getExitReason()).isEqualTo(DayLossStop.REASON);
            assertThat(t.getExitTimestamp()).isEqualTo(at(10, 10));
            assertThat(t.getExitSpotPrice()).isEqualTo(24961);
            assertThat(t.getEstimatedPnl()).isEqualTo(-39 * 65);
        }
        assertThat(r.kept()).containsExactly(a, b);
        assertThat(r.blocked()).containsExactly(later);
        assertThat(later.getSkipReason()).isEqualTo(DayLossStop.REASON);
        assertThat(stop.describe(5000)).isEqualTo("Max day loss ₹5,000 reached at 10:10 (−₹5,070): 2 open trades closed, 1 later entry not taken");
    }

    @Test
    void aClosedLossAloneStopsTheDaysLaterEntries() {
        NiftyTradeLog loser = trade("CE", at(9, 30), 25000, at(10, 0), 24900, "SL_HIT");   // −6500 when it closes
        NiftyTradeLog later = trade("PE", at(10, 30), 24970, at(11, 0), 24900, "TARGET_HIT");

        DayLossStop.Result r = DayLossStop.apply(List.of(loser, later), 5000.0, fallingNifty());

        assertThat(loser.getExitReason()).isEqualTo("SL_HIT");                  // closed by its own stop, untouched
        assertThat(r.blocked()).containsExactly(later);
        assertThat(r.stops().get(0).closed()).isZero();
    }

    @Test
    void aDayThatStaysAboveTheLimitIsUntouched() {
        NiftyTradeLog a = trade("CE", at(9, 30), 25000, at(10, 0), 24970, "SL_HIT");   // −1950 at worst
        DayLossStop.Result r = DayLossStop.apply(List.of(a), 5000.0, fallingNifty());
        assertThat(r.stops()).isEmpty();
        assertThat(r.kept()).containsExactly(a);
        assertThat(a.getExitReason()).isEqualTo("SL_HIT");
    }

    @Test
    void noLimitSetLeavesEveryTradeAsItWas() {
        NiftyTradeLog a = trade("CE", at(9, 30), 25000, at(11, 0), 24800, "SL_HIT");
        for (Double none : new Double[]{null, 0.0}) {
            DayLossStop.Result r = DayLossStop.apply(List.of(a), none, fallingNifty());
            assertThat(r.kept()).containsExactly(a);
            assertThat(r.stops()).isEmpty();
        }
        assertThat(a.getExitReason()).isEqualTo("SL_HIT");
    }

    @Test
    void openWinnersOffsetOpenLosers() {
        // A CE and a PE on the same fall: the PE gains what the CE loses, so the day never nears the limit.
        NiftyTradeLog ce = trade("CE", at(9, 30), 25000, at(14, 0), 24850, "SL_HIT");
        NiftyTradeLog pe = trade("PE", at(9, 30), 25000, at(14, 0), 24850, "TARGET_HIT");
        assertThat(DayLossStop.apply(List.of(ce, pe), 5000.0, fallingNifty()).stops()).isEmpty();
    }

    @Test
    void aTradeCountsFromWhenItsEntryPriceWasKnownNotItsCandlesStart() {
        // Stamped 09:25 (its 5-minute bar's start) at 24995, the close of the 09:35 minute: before that the index was
        // higher, so valuing it from 09:25 would show a loss that never happened.
        List<Candle> out = new ArrayList<>();
        for (Instant t = at(9, 15); t.isBefore(at(10, 0)); t = t.plusSeconds(60)) {
            BigDecimal p = BigDecimal.valueOf(t.isBefore(at(9, 35)) ? 24800 : 24995);
            out.add(new Candle(t, p, p, p, p, 0));
        }
        Map<String, PriceSeries> m = Map.of("NIFTY 50", new PriceSeries("NIFTY 50", Interval.MINUTE_1, out));
        NiftyTradeLog pe = trade("PE", at(9, 25), 24995, at(9, 50), 24990, "TARGET_HIT");
        // A PE at 24995 valued at 24800 would be +12675; a CE the other way round −12675: neither may count before 09:36.
        NiftyTradeLog ce = trade("CE", at(9, 25), 24995, at(9, 50), 24990, "SL_HIT");
        assertThat(DayLossStop.enteredAt(Map.of("NIFTY 50", new TreeMap<>(Map.of(at(9, 35), 24995.0))), ce)).isEqualTo(at(9, 36));
        DayLossStop.Result r = DayLossStop.apply(List.of(ce), 5000.0, m);
        assertThat(r.stops()).isEmpty();
        assertThat(ce.getExitReason()).isEqualTo("SL_HIT");
        assertThat(DayLossStop.apply(List.of(pe), 5000.0, m).stops()).isEmpty();
    }

    @Test
    void rupeesUseIndianGrouping() {
        assertThat(DayLossStop.rupees(100000)).isEqualTo("₹1,00,000");
        assertThat(DayLossStop.rupees(-5120)).isEqualTo("₹5,120");
        assertThat(DayLossStop.rupees(85)).isEqualTo("₹85");
        assertThat(DayLossStop.rupees(12345678)).isEqualTo("₹1,23,45,678");
    }
}
