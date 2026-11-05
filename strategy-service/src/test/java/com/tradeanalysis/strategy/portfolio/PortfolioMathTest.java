package com.tradeanalysis.strategy.portfolio;

import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.tradeanalysis.strategy.report.ReportBuilderTest.trade;
import static org.assertj.core.api.Assertions.assertThat;

class PortfolioMathTest {

    @Test
    void combinesTradesInTimeOrder() {
        LocalDate a = LocalDate.of(2026, 10, 5), b = LocalDate.of(2026, 10, 6), c = LocalDate.of(2026, 10, 7);
        // Given out of order (as when merging strategies): b, a, c → time order a(+5115), b(-2685), c(+565)
        List<NiftyTradeLog> trades = List.of(trade(b, 0, -40, "SL_HIT"), trade(a, 0, 80, "TARGET_HIT"), trade(c, 0, 10, "SL_HIT"));
        PortfolioMath.Summary s = PortfolioMath.summarize(trades);
        assertThat(s.trades()).isEqualTo(3);
        assertThat(s.wins()).isEqualTo(2);
        assertThat(s.netPnl()).isEqualTo(5115 - 2685 + 565);
        assertThat(s.charges()).isEqualTo(255);
        assertThat(s.maxDrawdown()).isEqualTo(-2685);
        assertThat(s.profitFactor()).isEqualTo((5115 + 565) / 2685.0);
    }

    @Test
    void noLosingTradesMeansNoProfitFactor() {
        assertThat(PortfolioMath.summarize(List.of(trade(LocalDate.of(2026, 10, 5), 0, 80, "TARGET_HIT"))).profitFactor()).isNull();
        assertThat(PortfolioMath.summarize(List.of()).trades()).isZero();
    }
}
