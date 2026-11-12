package com.tradeanalysis.strategy.portfolio;

import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioCapitalUseTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    static Instant at(int h, int m) { return ZonedDateTime.of(DAY, LocalTime.of(h, m), IST).toInstant(); }

    static NiftyTradeLog trade(int entryH, int entryM, int exitH, int exitM, double premium, int lots) {
        NiftyTradeLog t = new NiftyTradeLog(1L, "NIFTY 50", DAY, 1, "BREAKOUT", 24150, 24080, at(9, 15), at(entryH, entryM),
                24150, "CE", 24150, lots, 65, 24115);
        t.close(at(exitH, exitM), 24200, "TARGET_HIT", 24115);
        t.estimated(premium, null, 0.14, false);
        return t;
    }

    @Test
    void thePeakIsTheCapitalInUseAtOnceAcrossStrategies() {
        // A and B overlap (13,000 + 13,000 = 26,000 at once); C starts after both have left.
        NiftyTradeLog a = trade(9, 20, 10, 0, 200, 1);
        NiftyTradeLog b = trade(9, 30, 10, 30, 200, 1);
        NiftyTradeLog c = trade(10, 45, 11, 0, 200, 1);
        PortfolioMath.CapitalUse use = PortfolioMath.capitalUse(List.of(a, b, c));
        assertThat(use.peak()).isEqualTo(26_000.0);
        assertThat(use.max()).isEqualTo(13_000.0);
        assertThat(use.avg()).isEqualTo(13_000.0);
        assertThat(use.peakOn()).isEqualTo(DAY);
    }

    @Test
    void noTradesMeansNoCapitalFigures() {
        PortfolioMath.CapitalUse use = PortfolioMath.capitalUse(List.of());
        assertThat(use.peak()).isNull();
        assertThat(use.avg()).isNull();
    }

    @Test
    void theCapitalToFitAllCountsEveryTradeAtTheLotsItAskedFor() {
        // A asked for 2 lots but was cut to 1; B was left out. At full size they overlap: 26,000 + 13,000 = 39,000.
        NiftyTradeLog a = trade(9, 20, 10, 0, 200, 2);
        a.resize(1);
        NiftyTradeLog b = trade(9, 30, 10, 30, 200, 1);
        b.markSkipped("CAPITAL");
        NiftyTradeLog c = trade(10, 45, 11, 0, 200, 1);
        assertThat(PortfolioMath.capitalToFitAll(List.of(a, b, c))).isEqualTo(39_000.0);
        assertThat(PortfolioMath.capitalToFitAll(List.of())).isNull();
    }
}
