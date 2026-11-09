package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Trades of several strategies fitted to one capital pool (a portfolio), and the capital they use. */
class CapitalFitPortfolioTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    final CapitalFit fit = new CapitalFit(mock(OptionEstimates.class));

    static Instant at(int h, int m) { return ZonedDateTime.of(DAY, LocalTime.of(h, m), IST).toInstant(); }

    /** A closed CE trade of a strategy's backtest, asking for {@code requested} lots of 65 at the given premium. */
    static NiftyTradeLog trade(long backtestId, int entryH, int entryM, int exitH, int exitM, double premium, int requested) {
        NiftyTradeLog t = new NiftyTradeLog(1L, "NIFTY 50", DAY, 1, "BREAKOUT", 24150, 24080, at(9, 15), at(entryH, entryM),
                24150, "CE", 24150, requested, 65, 24115);
        t.setBacktestResultId(backtestId);
        t.close(at(exitH, exitM), 24200, "TARGET_HIT", 24115);
        t.estimated(premium, null, 0.14, false);
        return t;
    }

    @Test
    void twoStrategiesOverlappingShareTheCapitalAndTheLaterOneIsSkipped() {
        // Each asks for 2 lots of 65 at 200 (13,000 per lot, 26,000 each). Together they need 52,000; capital is 30,000.
        NiftyTradeLog ofA = trade(1L, 9, 20, 10, 0, 200, 2);
        NiftyTradeLog ofB = trade(2L, 9, 30, 10, 30, 200, 2);
        CapitalFit.Fit result = fit.fit(List.of(ofA, ofB), 30_000);
        assertThat(result.kept()).containsExactly(ofA);
        assertThat(ofA.getLots()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void aTradeCutToFewerLotsKeepsItsCapitalAndPnlInStep() {
        NiftyTradeLog ofA = trade(1L, 9, 20, 10, 0, 200, 2);
        NiftyTradeLog ofB = trade(2L, 9, 30, 10, 30, 200, 2);
        fit.fit(List.of(ofA, ofB), 20_000);   // A gets 1 lot (13,000 of 20,000); B finds 7,000 free, not one lot, so it is skipped
        assertThat(ofA.getLots()).isEqualTo(1);
        assertThat(ofA.getCapitalUsed()).isEqualTo(13_000.0);
    }

    @Test
    void fittingAgainStartsFromWhatWasRequestedSoTheResultIsTheSame() {
        NiftyTradeLog ofA = trade(1L, 9, 20, 10, 0, 200, 2);
        NiftyTradeLog ofB = trade(2L, 9, 30, 10, 30, 200, 2);
        fit.fit(List.of(ofA, ofB), 30_000);                    // B skipped here
        CapitalFit.Fit again = fit.fit(List.of(ofA, ofB), 100_000);   // room for both now
        assertThat(again.skipped()).isZero();
        assertThat(ofB.getLots()).isEqualTo(2);
        assertThat(ofA.getLots()).isEqualTo(2);
    }
}
