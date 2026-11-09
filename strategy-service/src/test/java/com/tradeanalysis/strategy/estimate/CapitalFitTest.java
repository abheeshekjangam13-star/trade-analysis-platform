package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CapitalFitTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    /** The estimates are set by hand here: the fitting only reads each trade's premium. */
    final CapitalFit fit = new CapitalFit(mock(OptionEstimates.class));

    static Instant at(int h, int m) { return ZonedDateTime.of(DAY, java.time.LocalTime.of(h, m), IST).toInstant(); }

    /** A closed CE trade, 2 lots of 65, at the given premium per unit. */
    static NiftyTradeLog trade(int entryH, int entryM, int exitH, int exitM, double premium, int lots) {
        NiftyTradeLog t = new NiftyTradeLog(1L, "NIFTY 50", DAY, 1, "BREAKOUT", 24150, 24080, at(9, 15), at(entryH, entryM),
                24150, "CE", 24150, lots, 65, 24115);
        t.close(at(exitH, exitM), 24200, "TARGET_HIT", 24115);
        t.estimated(premium, null, 0.14, false);
        return t;
    }

    @Test
    void skipsATradeWhenTheFreeCapitalCannotBuyALot() {
        // Each trade needs 200 × 65 = 13,000 per lot; 30,000 buys two lots of the first, not a second trade alongside.
        NiftyTradeLog first = trade(9, 20, 10, 0, 200, 2);
        NiftyTradeLog overlapping = trade(9, 30, 10, 30, 200, 2);
        CapitalFit.Fit result = fit.fit(java.util.List.of(first, overlapping), 30_000);
        assertThat(result.kept()).containsExactly(first);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void anExitAtTheSameInstantFreesTheCapitalForTheNextEntry() {
        NiftyTradeLog first = trade(9, 20, 10, 0, 200, 2);
        NiftyTradeLog next = trade(10, 0, 10, 40, 200, 2);
        CapitalFit.Fit result = fit.fit(java.util.List.of(first, next), 30_000);
        assertThat(result.kept()).containsExactly(first, next);
        assertThat(result.skipped()).isZero();
    }

    @Test
    void cutsTheLotsToWhatTheFreeCapitalBuys() {
        NiftyTradeLog t = trade(9, 20, 10, 0, 200, 2);   // 2 lots need 26,000; only 20,000 is there: 1 lot fits
        CapitalFit.Fit result = fit.fit(java.util.List.of(t), 20_000);
        assertThat(result.kept()).containsExactly(t);
        assertThat(t.getLots()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
    }

    @Test
    void leavesEverythingAsItIsWhenItAllFits() {
        NiftyTradeLog t = trade(9, 20, 10, 0, 200, 2);
        double pnl = t.getEstimatedPnl();
        CapitalFit.Fit result = fit.fit(java.util.List.of(t), 100_000);
        assertThat(t.getLots()).isEqualTo(2);
        assertThat(t.getEstimatedPnl()).isEqualTo(pnl);
        assertThat(result.skipped()).isZero();
    }
}
