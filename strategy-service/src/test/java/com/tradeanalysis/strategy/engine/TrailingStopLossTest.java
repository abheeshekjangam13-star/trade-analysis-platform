package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.strategy.engine.TrailingStopLoss.Direction;
import com.tradeanalysis.strategy.engine.TrailingStopLoss.UpdateOutcome;
import org.junit.jupiter.api.Test;

import static com.tradeanalysis.strategy.engine.Candles.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Entry on the 5-min breakout candle 09:25–09:30; trailing uses 1-min candles from 09:30. */
class TrailingStopLossTest {

    // Reference candle 09:15: high 24100, low 24000 → midpoint 24050
    private static final double REF_HIGH = 24100, REF_LOW = 24000;

    private static TrailingStopLoss bullish() { return TrailingStopLoss.open(Direction.BULLISH, REF_HIGH, REF_LOW, at(9, 25)); }
    private static TrailingStopLoss bearish() { return TrailingStopLoss.open(Direction.BEARISH, REF_HIGH, REF_LOW, at(9, 25)); }

    @Test
    void bullishInitialStopIsReferenceMidpoint() {
        assertThat(bullish().currentStop()).isEqualTo(24050.0);
    }

    @Test
    void bearishInitialStopIsReferenceMidpoint() {
        assertThat(bearish().currentStop()).isEqualTo(24050.0);
    }

    @Test
    void bullishTrailsToPreviousOneMinuteLow() {
        TrailingStopLoss sl = bullish();
        assertThat(sl.onCandle(bar(9, 30, 24120, 24135, 24110, 24130), close1m(9, 30))).isEqualTo(UpdateOutcome.UPDATED);
        assertThat(sl.currentStop()).isEqualTo(24110.0);
        sl.onCandle(bar(9, 31, 24130, 24145, 24125, 24140), close1m(9, 31));
        assertThat(sl.currentStop()).isEqualTo(24125.0);
    }

    @Test
    void bearishTrailsToPreviousOneMinuteHigh() {
        TrailingStopLoss sl = bearish();
        assertThat(sl.onCandle(bar(9, 30, 23980, 23990, 23970, 23975), close1m(9, 30))).isEqualTo(UpdateOutcome.UPDATED);
        assertThat(sl.currentStop()).isEqualTo(23990.0);
        sl.onCandle(bar(9, 31, 23975, 23982, 23960, 23965), close1m(9, 31));
        assertThat(sl.currentStop()).isEqualTo(23982.0);
    }

    @Test
    void bullishStopNeverDecreases() {
        TrailingStopLoss sl = bullish();
        sl.onCandle(bar(9, 30, 24120, 24135, 24110, 24130), close1m(9, 30));   // → 24110
        UpdateOutcome o = sl.onCandle(bar(9, 31, 24130, 24140, 24105, 24120), close1m(9, 31)); // low 24105 < 24110
        assertThat(o).isEqualTo(UpdateOutcome.UNCHANGED);
        assertThat(sl.currentStop()).isEqualTo(24110.0);
    }

    @Test
    void bearishStopNeverIncreases() {
        TrailingStopLoss sl = bearish();
        sl.onCandle(bar(9, 30, 23980, 23990, 23970, 23975), close1m(9, 30));   // → 23990
        UpdateOutcome o = sl.onCandle(bar(9, 31, 23975, 23995, 23965, 23985), close1m(9, 31)); // high 23995 > 23990
        assertThat(o).isEqualTo(UpdateOutcome.UNCHANGED);
        assertThat(sl.currentStop()).isEqualTo(23990.0);
    }

    @Test
    void incompleteCandleIsIgnored() {
        TrailingStopLoss sl = bullish();
        UpdateOutcome o = sl.onCandle(bar(9, 30, 24120, 24135, 24110, 24130), at(9, 30).plusSeconds(45));
        assertThat(o).isEqualTo(UpdateOutcome.IGNORED_INCOMPLETE);
        assertThat(sl.currentStop()).isEqualTo(24050.0);
        assertThat(sl.lastProcessedCandle()).isEqualTo(at(9, 29));
    }

    @Test
    void sameCandleIsNotAppliedTwice() {
        TrailingStopLoss sl = bullish();
        sl.onCandle(bar(9, 30, 24120, 24135, 24110, 24130), close1m(9, 30));
        // Same bar re-delivered (e.g. after reconnect) with a revised higher low.
        UpdateOutcome o = sl.onCandle(bar(9, 30, 24120, 24135, 24115, 24130), close1m(9, 31));
        assertThat(o).isEqualTo(UpdateOutcome.IGNORED_DUPLICATE);
        assertThat(sl.currentStop()).isEqualTo(24110.0);
    }

    @Test
    void oneMinuteCandlesInsideTheBreakoutCandleAreNeverUsed() {
        // Order placed at 09:30 (close of the 09:25 5-min candle); 09:25–09:29 1-min bars predate it.
        TrailingStopLoss sl = bullish();
        assertThat(sl.lastProcessedCandle()).isEqualTo(at(9, 29));
        assertThat(sl.onCandle(bar(9, 29, 24100, 24125, 24095, 24120), close1m(9, 29)))
                .isEqualTo(UpdateOutcome.IGNORED_DUPLICATE);
        assertThat(sl.currentStop()).isEqualTo(24050.0);
    }

    @Test
    void gapIsReportedAndStopIsNotMoved() {
        TrailingStopLoss sl = bullish();
        UpdateOutcome o = sl.onCandle(bar(9, 32, 24150, 24160, 24140, 24155), close1m(9, 32)); // 09:30, 09:31 missing
        assertThat(o).isEqualTo(UpdateOutcome.MISSING_CANDLES);
        assertThat(sl.currentStop()).isEqualTo(24050.0);
    }

    @Test
    void restoreKeepsPersistedStop() {
        TrailingStopLoss sl = TrailingStopLoss.restore(Direction.BULLISH, 24110, at(9, 40));
        assertThat(sl.currentStop()).isEqualTo(24110.0);
        assertThat(sl.onCandle(bar(9, 41, 24150, 24160, 24090, 24155), close1m(9, 41))).isEqualTo(UpdateOutcome.UNCHANGED);
        assertThat(sl.currentStop()).isEqualTo(24110.0);
    }

    @Test
    void bullishHitAtOrBelowStop() {
        TrailingStopLoss sl = TrailingStopLoss.restore(Direction.BULLISH, 24050, at(9, 29));
        assertThat(sl.isHit(24050.01)).isFalse();
        assertThat(sl.isHit(24050)).isTrue();
        assertThat(sl.isHit(24000)).isTrue();
    }

    @Test
    void bearishHitAtOrAboveStop() {
        TrailingStopLoss sl = TrailingStopLoss.restore(Direction.BEARISH, 24050, at(9, 29));
        assertThat(sl.isHit(24049.99)).isFalse();
        assertThat(sl.isHit(24050)).isTrue();
        assertThat(sl.isHit(24100)).isTrue();
    }

    @Test
    void fillPriceUsesOpenWhenCandleGapsThroughStop() {
        TrailingStopLoss sl = TrailingStopLoss.restore(Direction.BULLISH, 24050, at(9, 29));
        assertThat(sl.fillPrice(bar(9, 30, 24070, 24080, 24030, 24040))).isEqualTo(24050.0);
        assertThat(sl.fillPrice(bar(9, 30, 24020, 24030, 24000, 24010))).isEqualTo(24020.0);
    }

    @Test
    void fiveMinuteTrailUsesBlockLowOnlyWhenBlockCompletes() {
        TrailingStopLoss sl = TrailingStopLoss.open(Direction.BULLISH, REF_HIGH, REF_LOW, at(9, 25), 5);
        // 09:30–09:34 block; low 24105 at 09:31
        assertThat(sl.onCandle(bar(9, 30, 24120, 24135, 24110, 24130), close1m(9, 30))).isEqualTo(UpdateOutcome.ACCUMULATED);
        assertThat(sl.onCandle(bar(9, 31, 24130, 24140, 24105, 24135), close1m(9, 31))).isEqualTo(UpdateOutcome.ACCUMULATED);
        sl.onCandle(bar(9, 32, 24135, 24150, 24130, 24145), close1m(9, 32));
        sl.onCandle(bar(9, 33, 24145, 24160, 24140, 24155), close1m(9, 33));
        assertThat(sl.currentStop()).isEqualTo(24050.0);
        assertThat(sl.lastProcessedCandle()).isEqualTo(at(9, 29));      // persisted position only moves per block
        assertThat(sl.onCandle(bar(9, 34, 24155, 24170, 24150, 24165), close1m(9, 34))).isEqualTo(UpdateOutcome.UPDATED);
        assertThat(sl.currentStop()).isEqualTo(24105.0);
        assertThat(sl.lastProcessedCandle()).isEqualTo(at(9, 34));
    }

    @Test
    void bearishFiveMinuteTrailUsesBlockHigh() {
        TrailingStopLoss sl = TrailingStopLoss.open(Direction.BEARISH, REF_HIGH, REF_LOW, at(9, 25), 5);
        double[][] bars = {{23980, 23995, 23970, 23975}, {23975, 24010, 23965, 23970}, {23970, 23985, 23960, 23965},
                           {23965, 23975, 23950, 23955}, {23955, 23960, 23940, 23945}};
        for (int i = 0; i < 5; i++) {
            double[] b = bars[i];
            sl.onCandle(bar(9, 30 + i, b[0], b[1], b[2], b[3]), close1m(9, 30 + i));
        }
        assertThat(sl.currentStop()).isEqualTo(24010.0);
    }

    @Test
    void restoreReplaysPartialBlock() {
        // Persisted after the 09:34 block; 09:35–09:36 were accumulated then lost in a restart.
        TrailingStopLoss sl = TrailingStopLoss.restore(Direction.BULLISH, 24105, at(9, 34), 5);
        for (int m = 35; m <= 39; m++) sl.onCandle(bar(9, m, 24160, 24170, 24120 + m, 24165), close1m(9, m));
        assertThat(sl.currentStop()).isEqualTo(24155.0);   // min low of 09:35–09:39
    }
}
