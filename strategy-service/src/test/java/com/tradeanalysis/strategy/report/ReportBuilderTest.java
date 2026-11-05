package com.tradeanalysis.strategy.report;

import com.tradeanalysis.strategy.engine.NiftyOpeningBreakoutEngine.DayReport;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ReportBuilderTest {

    public static NiftyTradeLog trade(LocalDate day, int minute, double points, String reason) {
        Instant t = day.atTime(4, minute).toInstant(ZoneOffset.UTC);
        NiftyTradeLog log = new NiftyTradeLog(1L, "NIFTY 50", day, 1, "BULLISH", 0, 0, t, t, 1000, "CE", 950, 2, 65, 950);
        log.setContext(0.4, 85);
        log.close(t, 1000 + points, reason, 0);   // pnl = points × 65 − 85
        return log;
    }

    @Test
    void monthlyTotalsDrawdownAndRunningTotal() {
        LocalDate d1 = LocalDate.of(2026, 9, 1), d2 = LocalDate.of(2026, 9, 2), d3 = LocalDate.of(2026, 10, 1);
        List<NiftyTradeLog> trades = List.of(
                trade(d1, 0, 80, "TARGET_HIT"),        // +5115
                trade(d2, 0, -40, "SL_HIT"),           // -2685
                trade(d2, 30, -20, "FAILED_BREAKOUT"), // -1385
                trade(d3, 0, 10, "EOD_SQUAREOFF"));    // +565
        List<BacktestMonthlyStat> m = ReportBuilder.monthly(5L, List.of(new DayReport(LocalDate.of(2026, 8, 31), null, "NO_TRADE", "x", 0)), trades);

        assertThat(m).extracting(BacktestMonthlyStat::getPeriodMonth).containsExactly("2026-08", "2026-09", "2026-10");
        assertThat(m.get(0).getTrades()).isZero();
        BacktestMonthlyStat sep = m.get(1);
        assertThat(sep.getTrades()).isEqualTo(3);
        assertThat(sep.getWins()).isEqualTo(1);
        assertThat(sep.getLosses()).isEqualTo(2);
        assertThat(sep.getNetPnl()).isEqualTo(5115 - 2685 - 1385);
        assertThat(sep.getCharges()).isEqualTo(255);
        assertThat(sep.getGrossPnl()).isEqualTo(sep.getNetPnl() + 255);
        assertThat(sep.getAvgLoss()).isEqualTo((-2685 - 1385) / 2.0);
        assertThat(sep.getBestTrade()).isEqualTo(5115);
        assertThat(sep.getWorstTrade()).isEqualTo(-2685);
        assertThat(sep.getMaxDrawdown()).isEqualTo(-2685 - 1385);
        assertThat(List.of(sep.getTargetHits(), sep.getStopExits(), sep.getFailedBreakoutExits(), sep.getEodExits()))
                .containsExactly(1, 1, 1, 0);
        assertThat(m.get(2).getEodExits()).isEqualTo(1);
        assertThat(m.get(2).getCumulativePnl()).isEqualTo(5115 - 2685 - 1385 + 565);
    }

    @Test
    void dailyRowsCarryTheDayOutcomeAndItsPnl() {
        LocalDate mon = LocalDate.of(2026, 11, 2), tue = LocalDate.of(2026, 11, 3);
        List<BacktestDailyReport> d = ReportBuilder.daily(5L,
                List.of(new DayReport(mon, 0.73, "TRADED", null, 1), new DayReport(tue, 0.1, "NO_TRADE", "Gap too small", 0)),
                List.of(trade(mon, 0, 10, "SL_HIT")));
        assertThat(d).hasSize(2);
        assertThat(d.get(0).getDayOfWeek()).isEqualTo("MON");
        assertThat(d.get(0).getNetPnl()).isEqualTo(565);
        assertThat(d.get(1).getReason()).isEqualTo("Gap too small");
        assertThat(d.get(1).getTrades()).isZero();
    }
}
