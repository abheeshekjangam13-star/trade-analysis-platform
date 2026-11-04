package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tradeanalysis.strategy.engine.Candles.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BacktestEngineTest {

    /** CE trade with 2 lots × 65 at 0.5 delta: P&L = spot move × 65. */
    private static NiftyTradeLog trade(double pnl) {
        NiftyTradeLog t = new NiftyTradeLog(1L, "NIFTY 50", DAY, 1, "BULLISH", 24100, 24000,
                at(9, 15), at(9, 25), 24000, "CE", 23950, 2, 65, 24050);
        t.close(at(9, 40), 24000 + pnl / 65, "SL_HIT", 24050);
        return t;
    }

    @Test
    void drawdownIsMeasuredAgainstAccountEquity() {
        // Equity 100000 → 101000 (peak) → 96000 → 98000: max drawdown 5000 / 101000
        double dd = BacktestEngine.maxDrawdownPct(100_000, List.of(trade(1000), trade(-5000), trade(2000)));
        assertThat(dd).isCloseTo(5000.0 / 101_000 * 100, within(1e-9));
    }

    @Test
    void lossesFromTheStartCountAgainstInitialCapital() {
        double dd = BacktestEngine.maxDrawdownPct(100_000, List.of(trade(-2000), trade(-3000)));
        assertThat(dd).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void noTradesMeansNoDrawdown() {
        assertThat(BacktestEngine.maxDrawdownPct(100_000, List.of())).isZero();
    }
}
