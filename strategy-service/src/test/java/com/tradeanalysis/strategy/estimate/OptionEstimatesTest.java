package com.tradeanalysis.strategy.estimate;

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OptionEstimatesTest {

    @Test
    void blackScholesMatchesAReferenceValueAndPutCallParity() {
        // S=100, K=100, 1 year, 20% vol, r=6.5%: call = 11.2639 (checked independently)
        double call = OptionEstimates.price(true, 100, 100, 1, 0.20), put = OptionEstimates.price(false, 100, 100, 1, 0.20);
        assertThat(call).isCloseTo(11.2639, within(0.0005));
        assertThat(call - put).isCloseTo(100 - 100 * Math.exp(-OptionEstimates.RATE), within(1e-6));   // C − P = S − K·e^(−rT)
    }

    @Test
    void niftyWeeklyOptionsPriceSensibly() {
        // NIFTY 25,800, CE strike 25,750 (ATM−50), 4 days to expiry, VIX 13
        double years = 4 / 365.0;
        double ce = OptionEstimates.price(true, 25800, 25750, years, 0.13);
        assertThat(ce).isBetween(150.0, 300.0);                       // 50 in the money plus time value
        assertThat(OptionEstimates.price(true, 25835, 25750, years, 0.13) - ce).isBetween(17.0, 30.0);   // +35 pts earns about 0.6×
        assertThat(OptionEstimates.price(true, 25800, 25750, 0, 0.13)).isEqualTo(50.0);   // at expiry: intrinsic value
    }

    @Test
    void weeklyExpiryMovedFromThursdayToTuesday() {
        assertThat(OptionEstimates.weeklyExpiry(LocalDate.of(2024, 11, 1))).isEqualTo(LocalDate.of(2024, 11, 7));   // Fri → Thu
        assertThat(OptionEstimates.weeklyExpiry(LocalDate.of(2025, 8, 28))).isEqualTo(LocalDate.of(2025, 8, 28));  // Thu itself
        assertThat(OptionEstimates.weeklyExpiry(LocalDate.of(2026, 11, 5))).isEqualTo(LocalDate.of(2026, 11, 10)); // Thu → Tue
        assertThat(OptionEstimates.weeklyExpiry(LocalDate.of(2026, 11, 10))).isEqualTo(LocalDate.of(2026, 11, 10));
    }

    @Test
    void yearsToExpiryHasAFloorAndStrikesFollowTheStrategy() {
        Instant expiryClose = LocalDate.of(2026, 11, 10).atTime(15, 30).atZone(OptionEstimates.IST).toInstant();
        assertThat(OptionEstimates.yearsToExpiry(expiryClose, LocalDate.of(2026, 11, 10))).isCloseTo(1800 / (365.0 * 86_400), within(1e-12));
        assertThat(OptionEstimates.strike(true, 25812)).isEqualTo(25750);    // ATM 25,800 − 50
        assertThat(OptionEstimates.strike(false, 25812)).isEqualTo(25850);   // ATM 25,800 + 50
    }

    @Test
    void aRupeeMaxLossBecomesStopPointsThatLoseAboutThatMuch() {
        double years = 2 / 365.0, spot = 25800;
        for (boolean call : new boolean[]{true, false}) {
            Double pts = StrategyPreviewController.pointsForLoss(com.tradeanalysis.common.market.Underlying.NIFTY, call, spot, years, 0.13, 130, 85, 2000);
            int k = OptionEstimates.strike(call, spot);
            double premium = OptionEstimates.price(call, spot, k, years, 0.13);
            double loss = (premium - OptionEstimates.price(call, call ? spot - pts : spot + pts, k, years, 0.13)) * 130 + 85;
            assertThat(loss).isBetween(1900.0, 2000.0);                     // within the amount, and close to it
            assertThat(pts * 2).isEqualTo(Math.floor(pts * 2));             // half-point steps
        }
        assertThat(StrategyPreviewController.pointsForLoss(com.tradeanalysis.common.market.Underlying.NIFTY, true, spot, years, 0.13, 130, 85, 1_000_000)).isNull();   // more than the premium
    }

    @Test
    void aTradeOfABacktestNotYetSavedHasNoIdAndStillGetsAPremium() {
        // A running backtest's trades have no id yet; the capital lookup must not fail on the null key.
        OptionEstimates estimates = new OptionEstimates(org.mockito.Mockito.mock(com.tradeanalysis.strategy.engine.MarketDataClient.class));
        com.tradeanalysis.strategy.model.NiftyTradeLog t = new com.tradeanalysis.strategy.model.NiftyTradeLog(null, "NIFTY 50", LocalDate.of(2026, 11, 3), 1,
                "BREAKOUT", 24150, 24080, Instant.parse("2026-11-03T03:50:00Z"), Instant.parse("2026-11-03T03:50:00Z"),
                24150, "CE", 24150, 2, 65, 24115);
        estimates.estimate(java.util.List.of(t), java.util.Map.of());
        assertThat(t.getEstEntryPremium()).isNotNull().isPositive();
        assertThat(t.getCapitalUsedPct()).isNull();   // no backtest capital to compare with yet
    }
}
