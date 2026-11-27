package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.tradeanalysis.strategy.estimate.DayLossStopTest.DAY;
import static com.tradeanalysis.strategy.estimate.DayLossStopTest.at;
import static org.assertj.core.api.Assertions.assertThat;

/** A second buy of an option another strategy already bought that day waits, then buys at that moment's price. */
class SameStrikeWaitTest {

    /** NIFTY 25000 until 09:35, then up a point a minute. */
    static Map<String, PriceSeries> rising() {
        List<Candle> out = new ArrayList<>();
        for (Instant t = at(9, 15); t.isBefore(at(15, 30)); t = t.plusSeconds(60)) {
            long k = Math.max(0, Duration.between(at(9, 35), t).toMinutes());
            BigDecimal p = BigDecimal.valueOf(25000 + k);
            out.add(new Candle(t, p, p, p, p, 0));
        }
        return Map.of("NIFTY 50", new PriceSeries("NIFTY 50", Interval.MINUTE_1, out));
    }

    /** A 2-lot CE 25000 of {@code strategy} on the 5-minute signal candle stamped {@code signal}, entered at 25000. */
    static NiftyTradeLog trade(long strategy, Instant signal, Instant exit, double exitSpot, String reason) {
        NiftyTradeLog t = new NiftyTradeLog(strategy, "NIFTY 50", DAY, 1, "BULLISH", 25010, 24990, at(9, 15), signal, 25000,
                "CE", 25000, 2, 65, 24900);
        t.setContext(0.1, 0);
        t.close(exit, exitSpot, reason, 24900);
        return t;
    }

    @Test
    void theSecondBuyWaitsAndPaysThePriceAfterTheWait() {
        NiftyTradeLog first = trade(261, at(9, 30), at(11, 0), 25100, "TARGET_HIT");
        NiftyTradeLog second = trade(257, at(9, 30), at(11, 0), 25100, "TARGET_HIT");
        double before = second.getEstimatedPnl();

        assertThat(SameStrikeWait.apply(List.of(first, second), 3, rising())).isEmpty();

        // Same minute: the lower id (257) buys first, 261 waits 3 minutes: bought at 09:38, the 09:37 close is 25002.
        assertThat(second.getEntryDelayMinutes()).isNull();
        assertThat(first.getEntryDelayMinutes()).isEqualTo(3);
        assertThat(first.getEntrySpotPrice()).isEqualTo(25002);
        assertThat(first.getEstimatedPnl()).isEqualTo(before - 2 * 65 * 0.5 * 2, org.assertj.core.data.Offset.offset(0.01));
        assertThat(second.getEstimatedPnl()).isEqualTo(before);
    }

    @Test
    void aTradeOverDuringTheWaitIsDropped() {
        NiftyTradeLog first = trade(257, at(9, 30), at(11, 0), 25100, "TARGET_HIT");
        NiftyTradeLog stopped = trade(261, at(9, 30), at(9, 37), 24900, "SL_HIT");   // stop in the minute 09:37–09:38

        assertThat(SameStrikeWait.apply(List.of(first, stopped), 3, rising())).containsExactly(stopped);
        assertThat(stopped.getSkipReason()).isEqualTo(SameStrikeWait.REASON);
        assertThat(first.getSkipReason()).isNull();
    }

    @Test
    void laterTheSameDayAndOnlyTheSameOptionCounts() {
        NiftyTradeLog morning = trade(257, at(9, 30), at(9, 50), 24950, "SL_HIT");
        NiftyTradeLog later = trade(261, at(10, 0), at(11, 0), 25100, "TARGET_HIT");   // same option, after the first closed
        NiftyTradeLog put = new NiftyTradeLog(262L, "NIFTY 50", DAY, 1, "BEARISH", 25010, 24990, at(9, 15), at(10, 0), 25000,
                "PE", 25000, 2, 65, 25100);
        put.setContext(0.1, 0);
        put.close(at(11, 0), 24900, "TARGET_HIT", 25100);

        SameStrikeWait.apply(List.of(morning, later, put), 3, rising());

        assertThat(later.getEntryDelayMinutes()).isEqualTo(3);
        assertThat(put.getEntryDelayMinutes()).isNull();
        assertThat(morning.getEntryDelayMinutes()).isNull();
    }

    @Test
    void offChangesNothing() {
        NiftyTradeLog a = trade(257, at(9, 30), at(11, 0), 25100, "TARGET_HIT");
        NiftyTradeLog b = trade(261, at(9, 30), at(9, 37), 24900, "SL_HIT");
        assertThat(SameStrikeWait.apply(List.of(a, b), null, rising())).isEmpty();
        assertThat(b.getSkipReason()).isNull();
        assertThat(b.getEntryDelayMinutes()).isNull();
    }
}
