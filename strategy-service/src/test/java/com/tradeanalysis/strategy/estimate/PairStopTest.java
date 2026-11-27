package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.tradeanalysis.strategy.estimate.DayLossStopTest.at;
import static com.tradeanalysis.strategy.estimate.DayLossStopTest.trade;
import static org.assertj.core.api.Assertions.assertThat;

/** Two strategies holding the same option from the same minute: one's losing stop closes the other. */
class PairStopTest {

    /** NIFTY 25000 until 09:30, down a point a minute to 24940 at 10:30, then up a point a minute. */
    static Map<String, PriceSeries> vShape() {
        List<Candle> out = new ArrayList<>();
        for (Instant t = at(9, 15); t.isBefore(at(15, 30)); t = t.plusSeconds(60)) {
            long k = Math.max(0, Duration.between(at(9, 30), t).toMinutes());
            BigDecimal p = BigDecimal.valueOf(k <= 60 ? 25000 - k : 24940 + (k - 60));
            out.add(new Candle(t, p, p, p, p, 0));
        }
        return Map.of("NIFTY 50", new PriceSeries("NIFTY 50", Interval.MINUTE_1, out));
    }

    @Test
    void atZeroPointsThePartnerClosesWithTheLosingStop() {
        NiftyTradeLog a = trade("CE", at(9, 30), 25000, at(9, 50), 24980, "SL_HIT");
        NiftyTradeLog b = trade("CE", at(9, 30), 25000, at(10, 30), 24940, "SL_HIT");

        assertThat(PairStop.apply(List.of(a, b), 0, vShape())).containsExactly(b);

        assertThat(b.getExitReason()).isEqualTo(PairStop.REASON);
        assertThat(b.getExitTimestamp()).isEqualTo(at(9, 50));
        assertThat(b.getExitSpotPrice()).isEqualTo(24980);
        assertThat(b.getEstimatedPnl()).isEqualTo(a.getEstimatedPnl());
        assertThat(a.getExitReason()).isEqualTo("SL_HIT");
    }

    @Test
    void withAWaitThePartnerClosesOnceTheIndexGoesThatMuchFurther() {
        NiftyTradeLog a = trade("CE", at(9, 30), 25000, at(9, 50), 24980, "SL_HIT");
        NiftyTradeLog b = trade("CE", at(9, 30), 25000, at(10, 30), 24940, "SL_HIT");

        assertThat(PairStop.apply(List.of(a, b), 15, vShape())).containsExactly(b);

        assertThat(b.getExitReason()).isEqualTo(PairStop.REASON);
        assertThat(b.getExitTimestamp()).isEqualTo(at(10, 5));   // 24965 is reached 35 minutes after 09:30
        assertThat(b.getExitSpotPrice()).isEqualTo(24965);
    }

    @Test
    void aWaitNeverReachedBeforeThePartnersOwnExitLeavesIt() {
        NiftyTradeLog a = trade("CE", at(9, 30), 25000, at(10, 25), 24945, "SL_HIT");
        NiftyTradeLog b = trade("CE", at(9, 30), 25000, at(12, 0), 25030, "TARGET_HIT");

        assertThat(PairStop.apply(List.of(a, b), 20, vShape())).isEmpty();   // 24925 is never reached
        assertThat(b.getExitReason()).isEqualTo("TARGET_HIT");
    }

    @Test
    void onlyALosingStopOnTheSameOptionAndMinuteCounts() {
        NiftyTradeLog winner = trade("CE", at(9, 30), 25000, at(9, 40), 25010, "TARGET_HIT");
        NiftyTradeLog partner = trade("CE", at(9, 30), 25000, at(10, 30), 24940, "SL_HIT");
        NiftyTradeLog otherMinute = trade("CE", at(9, 35), 24995, at(10, 30), 24940, "SL_HIT");
        NiftyTradeLog put = trade("PE", at(9, 30), 25000, at(11, 0), 24900, "TARGET_HIT");
        NiftyTradeLog stopped = trade("CE", at(9, 35), 24995, at(9, 50), 24980, "SL_HIT");

        List<NiftyTradeLog> closed = PairStop.apply(List.of(winner, partner, otherMinute, put, stopped), 0, vShape());

        assertThat(closed).containsExactly(otherMinute);   // stopped's partner from 09:35; the 09:30 pair had a winner
        assertThat(partner.getExitReason()).isEqualTo("SL_HIT");
        assertThat(put.getExitReason()).isEqualTo("TARGET_HIT");
    }

    @Test
    void offChangesNothing() {
        NiftyTradeLog a = trade("CE", at(9, 30), 25000, at(9, 50), 24980, "SL_HIT");
        NiftyTradeLog b = trade("CE", at(9, 30), 25000, at(10, 30), 24940, "SL_HIT");
        assertThat(PairStop.apply(List.of(a, b), null, vShape())).isEmpty();
        assertThat(b.getExitReason()).isEqualTo("SL_HIT");
    }

    /**
     * The partner closed early keeps the day under the max day loss, so a later strategy's entry is still taken and can
     * win back the loss (the max day loss runs after the pair stop, as in a portfolio report).
     */
    @Test
    void closingThePartnerEarlyCanKeepTheDayUnderTheLimitSoLaterTradesStillRun() {
        double limit = 3000;
        // Without the pair stop: a −1300 plus b open, falling, pass −3000 at 09:57; the 10:35 trade is never taken.
        List<NiftyTradeLog> without = day();
        DayLossStop.Result off = DayLossStop.apply(without, limit, vShape());
        assertThat(off.stops()).hasSize(1);
        assertThat(off.kept()).doesNotContain(without.get(2));

        // With it: b closes with a at −1300 each (−2600 < 3000), so the later trade runs to its target.
        List<NiftyTradeLog> with = day();
        PairStop.apply(with, 0, vShape());
        DayLossStop.Result on = DayLossStop.apply(with, limit, vShape());
        assertThat(on.stops()).isEmpty();
        assertThat(on.kept()).contains(with.get(2));
        assertThat(with.get(2).getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(on.kept().stream().mapToDouble(NiftyTradeLog::getEstimatedPnl).sum())
                .isGreaterThan(off.kept().stream().mapToDouble(NiftyTradeLog::getEstimatedPnl).sum());
    }

    private static List<NiftyTradeLog> day() {
        return new ArrayList<>(List.of(
                trade("CE", at(9, 30), 25000, at(9, 50), 24980, "SL_HIT"),
                trade("CE", at(9, 30), 25000, at(10, 30), 24940, "SL_HIT"),
                trade("CE", at(10, 35), 24945, at(12, 0), 25030, "TARGET_HIT")));
    }
}
