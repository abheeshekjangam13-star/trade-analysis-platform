package com.tradeanalysis.strategy.engine;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.tradeanalysis.strategy.engine.Candles.*;
import static org.assertj.core.api.Assertions.assertThat;

class NiftyOpeningBreakoutEngineTest {

    private final NiftyOpeningBreakoutEngine engine = new NiftyOpeningBreakoutEngine();
    private final Params params = new Params(Map.of("lots", "2", "lotSize", "65"));

    // Reference 09:15 candle: high 24100, low 24000 → initial SL 24050
    private static final Candle REF = bar(9, 15, 24050, 24100, 24000, 24060);

    private NiftyOpeningBreakoutEngine.BreakoutResult run(List<Candle> fiveMin, List<Candle> oneMin, Instant now) {
        return engine.run(new PriceSeries("NIFTY 50", Interval.MINUTE_5, fiveMin),
                new PriceSeries("NIFTY 50", Interval.MINUTE_1, oneMin), params, 1L, now);
    }

    @Test
    void bullishTradeTrailsOnOneMinuteLowsAndExitsAtTrailedStop() {
        List<Candle> fiveMin = List.of(REF,
                bar(9, 20, 24060, 24090, 24040, 24080),
                bar(9, 25, 24080, 24130, 24070, 24120),   // fresh close > refHigh → CE entry at 09:30, SL 24050
                bar(9, 30, 24120, 24145, 24110, 24125));
        List<Candle> oneMin = List.of(
                bar(9, 29, 24110, 24125, 24070, 24120),   // inside the breakout candle: ignored
                bar(9, 30, 24120, 24135, 24110, 24130),   // SL → 24110
                bar(9, 31, 24130, 24145, 24125, 24140),   // SL → 24125
                bar(9, 32, 24140, 24142, 24120, 24125));  // low 24120 ≤ 24125 → SL hit

        List<NiftyTradeLog> trades = run(fiveMin, oneMin, closeOf(9, 30)).tradeLogs();

        assertThat(trades).hasSize(1);
        NiftyTradeLog t = trades.get(0);
        assertThat(t.getOptionType()).isEqualTo("CE");
        assertThat(t.getStrikePrice()).isEqualTo(24050);           // ATM 24100 − 50
        assertThat(t.getInitialStopLoss()).isEqualTo(24050.0);     // midpoint, not breakout low 24070
        assertThat(t.getFinalStopLoss()).isEqualTo(24125.0);
        assertThat(t.getExitReason()).isEqualTo("SL_HIT");
        assertThat(t.getExitTimestamp()).isEqualTo(at(9, 32));
        assertThat(t.getExitSpotPrice()).isEqualTo(24125.0);
        assertThat(t.getEstimatedPnl()).isEqualTo((24125 - 24120) * 65 * 2 * 0.5);
    }

    @Test
    void bearishTradeTrailsOnOneMinuteHighsAndExitsAtTrailedStop() {
        List<Candle> fiveMin = List.of(REF,
                bar(9, 20, 24060, 24065, 23980, 23990),   // fresh close < refLow → PE entry at 09:25, SL 24050
                bar(9, 25, 23990, 23995, 23960, 23985));
        List<Candle> oneMin = List.of(
                bar(9, 25, 23990, 23992, 23970, 23975),   // SL → 23992
                bar(9, 26, 23975, 23980, 23960, 23965),   // SL → 23980
                bar(9, 27, 23978, 23995, 23970, 23985));  // high 23995 ≥ 23980 → SL hit, opened below stop

        NiftyTradeLog t = run(fiveMin, oneMin, closeOf(9, 25)).tradeLogs().get(0);

        assertThat(t.getOptionType()).isEqualTo("PE");
        assertThat(t.getStrikePrice()).isEqualTo(24050);           // ATM 24000 + 50
        assertThat(t.getInitialStopLoss()).isEqualTo(24050.0);
        assertThat(t.getFinalStopLoss()).isEqualTo(23980.0);
        assertThat(t.getExitTimestamp()).isEqualTo(at(9, 27));
        assertThat(t.getExitSpotPrice()).isEqualTo(23980.0);
    }

    @Test
    void stopStaysAtMidpointWhenOneMinuteDataIsMissing() {
        List<Candle> fiveMin = List.of(REF,
                bar(9, 20, 24060, 24130, 24050, 24120),   // CE entry, SL 24050
                bar(9, 25, 24120, 24180, 24060, 24170),   // not hit; no 1-min data so no trailing
                bar(9, 30, 24170, 24175, 24045, 24060));  // low 24045 ≤ 24050 → SL hit on 5-min fallback

        NiftyTradeLog t = run(fiveMin, List.of(), closeOf(9, 30)).tradeLogs().get(0);
        assertThat(t.getFinalStopLoss()).isEqualTo(24050.0);
        assertThat(t.getExitTimestamp()).isEqualTo(at(9, 30));
        assertThat(t.getExitSpotPrice()).isEqualTo(24050.0);
    }

    @Test
    void stillFormingCandleDoesNotTriggerEntry() {
        List<Candle> candles = List.of(REF,
                bar(9, 20, 24060, 24090, 24040, 24080),
                bar(9, 25, 24080, 24130, 24070, 24120));  // would be a breakout once it closes at 09:30

        assertThat(run(candles, List.of(), at(9, 28)).signals()).isEmpty();
    }

    @Test
    void openPositionIsSquaredOffAt1520() {
        List<Candle> candles = List.of(REF,
                bar(9, 20, 24060, 24130, 24050, 24120),   // CE entry
                bar(15, 15, 24300, 24320, 24290, 24310),
                bar(15, 20, 24305, 24310, 24300, 24300)); // EOD exit at open

        NiftyTradeLog t = run(candles, List.of(), closeOf(15, 20)).tradeLogs().get(0);
        assertThat(t.getExitReason()).isEqualTo("EOD_SQUAREOFF");
        assertThat(t.getExitSpotPrice()).isEqualTo(24305.0);
    }

    @Test
    void secondTradeNeedsFreshBreakoutAndDailyCapIsTwo() {
        List<Candle> candles = List.of(REF,
                bar(9, 20, 24060, 24130, 24060, 24120),   // trade 1: CE
                bar(9, 25, 24120, 24125, 24040, 24045),   // SL 24050 hit; close 24045 is not a fresh break
                bar(9, 30, 24045, 24050, 23980, 23990),   // trade 2: fresh bearish → PE, SL 24050
                bar(9, 35, 23990, 24060, 23985, 24055),   // SL hit (high 24060 ≥ 24050)
                bar(9, 40, 24055, 24060, 23970, 23980),   // fresh bearish again, but cap reached
                bar(9, 45, 23980, 24130, 23975, 24120));

        List<NiftyTradeLog> trades = run(candles, List.of(), closeOf(9, 45)).tradeLogs();
        assertThat(trades).extracting(NiftyTradeLog::getOptionType).containsExactly("CE", "PE");
    }

    // ── Configurable rules (CE-only gap-up variant) ─────────────────────────

    private static Params params(String... kv) {
        Map<String, Object> m = new HashMap<>(Map.of("lots", "2", "lotSize", "65"));
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new Params(m);
    }

    private NiftyOpeningBreakoutEngine.BreakoutResult run(Params p, List<Candle> fiveMin, List<Candle> oneMin,
                                                           Instant now, LocalDate tradeFrom) {
        return engine.run(new PriceSeries("NIFTY 50", Interval.MINUTE_5, fiveMin),
                new PriceSeries("NIFTY 50", Interval.MINUTE_1, oneMin), p, 1L, now, tradeFrom);
    }

    @Test
    void ceOnlySkipsBearishBreakouts() {
        List<Candle> candles = List.of(REF,
                bar(9, 20, 24060, 24065, 23980, 23990),   // bearish breakout: ignored
                bar(9, 25, 23990, 24040, 23985, 24030),
                bar(9, 30, 24030, 24130, 24020, 24120),   // bullish breakout: CE
                bar(9, 35, 24120, 24125, 24040, 24045));  // SL 24050 hit

        List<NiftyTradeLog> trades = run(params("directions", "CE"), candles, List.of(), closeOf(9, 35), null).tradeLogs();
        assertThat(trades).extracting(NiftyTradeLog::getOptionType).containsExactly("CE");
    }

    @Test
    void maxOneTradePerDay() {
        List<Candle> candles = List.of(REF,
                bar(9, 20, 24060, 24130, 24060, 24120),   // CE
                bar(9, 25, 24120, 24125, 24040, 24045),   // SL hit
                bar(9, 30, 24045, 24050, 23980, 23990));  // fresh bearish, but cap is 1

        List<NiftyTradeLog> trades = run(params("maxTradesPerDay", "1"), candles, List.of(), closeOf(9, 30), null).tradeLogs();
        assertThat(trades).hasSize(1);
    }

    @Test
    void entryFromIgnoresBreakoutsBeforeTheWindowAndNeedsAFreshOneAfter() {
        List<Candle> candles = List.of(REF,
                bar(9, 20, 24060, 24130, 24060, 24120),     // breakout before 10:30: ignored
                bar(10, 25, 24120, 24125, 24110, 24115),    // still above refHigh
                bar(10, 30, 24115, 24130, 24105, 24125),    // in window but not fresh (prev close > refHigh)
                bar(10, 35, 24125, 24126, 24080, 24090),    // back below refHigh
                bar(10, 40, 24090, 24140, 24085, 24135));   // fresh breakout in window: CE

        List<NiftyTradeLog> trades = run(params("entryFrom", "10:30"), candles, List.of(), closeOf(10, 40), null).tradeLogs();
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).getEntryTimestamp()).isEqualTo(candles.get(5).timestamp());
    }

    @Test
    void entryFromAllowsAFreshBreakoutOnTheFirstCandleOfTheWindow() {
        List<Candle> candles = List.of(REF,
                bar(10, 25, 24060, 24090, 24050, 24080),
                bar(10, 30, 24080, 24140, 24075, 24135));   // fresh at 10:30 exactly

        List<NiftyTradeLog> trades = run(params("entryFrom", "10:30"), candles, List.of(), closeOf(10, 30), null).tradeLogs();
        assertThat(trades).hasSize(1);
    }

    @Test
    void targetExitsAtEntryPlusPoints() {
        List<Candle> fiveMin = List.of(REF,
                bar(9, 20, 24060, 24130, 24060, 24120),   // CE entry 24120, target 24240
                bar(9, 25, 24120, 24260, 24110, 24250));
        List<Candle> oneMin = List.of(
                bar(9, 25, 24120, 24180, 24110, 24170),
                bar(9, 26, 24170, 24260, 24165, 24250));  // high 24260 ≥ 24240

        NiftyTradeLog t = run(params("targetPoints", "120"), fiveMin, oneMin, closeOf(9, 25), null).tradeLogs().get(0);
        assertThat(t.getExitReason()).isEqualTo("TARGET_HIT");
        assertThat(t.getExitSpotPrice()).isEqualTo(24240.0);
        assertThat(t.getExitTimestamp()).isEqualTo(at(9, 26));
    }

    @Test
    void stopIsCheckedBeforeTargetWithinTheSameMinute() {
        List<Candle> fiveMin = List.of(REF,
                bar(9, 20, 24060, 24130, 24060, 24120),
                bar(9, 25, 24120, 24260, 24040, 24250));
        List<Candle> oneMin = List.of(bar(9, 25, 24120, 24260, 24040, 24250)); // both SL and target in one bar

        NiftyTradeLog t = run(params("targetPoints", "120"), fiveMin, oneMin, closeOf(9, 25), null).tradeLogs().get(0);
        assertThat(t.getExitReason()).isEqualTo("SL_HIT");
    }

    @Test
    void chargesAreDeductedFromPnl() {
        List<Candle> fiveMin = List.of(REF,
                bar(9, 20, 24060, 24130, 24060, 24120),
                bar(9, 25, 24120, 24260, 24110, 24250));
        List<Candle> oneMin = List.of(bar(9, 25, 24120, 24245, 24110, 24240));

        NiftyTradeLog t = run(params("targetPoints", "120", "costPerTrade", "85"), fiveMin, oneMin, closeOf(9, 25), null)
                .tradeLogs().get(0);
        assertThat(t.getCharges()).isEqualTo(85.0);
        assertThat(t.getEstimatedPnl()).isEqualTo(120 * 65 * 2 * 0.5 - 85);
    }

    @Test
    void gapUpFilterTradesOnlyGapUpDaysAndUsesPriorDayForTheGap() {
        LocalDate d1 = LocalDate.of(2026, 10, 26), d2 = LocalDate.of(2026, 10, 27), d3 = LocalDate.of(2026, 10, 28);
        List<Candle> candles = new ArrayList<>();
        // d1: only supplies the previous close (before tradeFrom), would otherwise trade
        candles.add(barOn(d1, 9, 15, 23900, 24000, 23880, 23950));
        candles.add(barOn(d1, 9, 20, 23950, 24050, 23940, 24040));
        candles.add(barOn(d1, 15, 25, 24000, 24010, 23990, 24000));   // close 24000
        // d2: opens 24100 = +0.42% gap-up → trades
        candles.add(barOn(d2, 9, 15, 24100, 24150, 24080, 24120));
        candles.add(barOn(d2, 9, 20, 24120, 24200, 24110, 24190));    // CE
        candles.add(barOn(d2, 9, 25, 24190, 24195, 24100, 24110));    // SL 24115 hit
        candles.add(barOn(d2, 15, 25, 24050, 24060, 24040, 24050));   // close 24050
        // d3: opens 24060 = +0.04% (flat) → skipped
        candles.add(barOn(d3, 9, 15, 24060, 24100, 24040, 24070));
        candles.add(barOn(d3, 9, 20, 24070, 24150, 24065, 24140));

        Instant now = d3.atTime(15, 30).atZone(IST).toInstant();
        List<NiftyTradeLog> trades = run(params("directions", "CE", "gapFilter", "GAP_UP", "gapPct", "0.2"),
                candles, List.of(), now, d2).tradeLogs();

        assertThat(trades).extracting(NiftyTradeLog::getTradeDate).containsExactly(d2);
        assertThat(trades.get(0).getGapPercent()).isCloseTo(0.4167, org.assertj.core.api.Assertions.within(1e-3));
    }

    @Test
    void gapFilterSkipsDayWithoutPreviousClose() {
        List<Candle> candles = List.of(REF, bar(9, 20, 24060, 24130, 24060, 24120));
        assertThat(run(params("gapFilter", "ANY_GAP"), candles, List.of(), closeOf(9, 20), null).tradeLogs()).isEmpty();
    }

    @Test
    void fiveMinuteTrailInBacktestUsesBlockLow() {
        List<Candle> fiveMin = List.of(REF,
                bar(9, 20, 24060, 24130, 24060, 24120),   // CE entry, SL 24050
                bar(9, 25, 24120, 24180, 24090, 24170),   // block 09:25–09:29 low 24090 → SL 24090
                bar(9, 30, 24170, 24175, 24080, 24100));  // hit at 09:31
        List<Candle> oneMin = new ArrayList<>(List.of(
                bar(9, 25, 24120, 24140, 24100, 24130),
                bar(9, 26, 24130, 24150, 24090, 24145),
                bar(9, 27, 24145, 24160, 24120, 24155),
                bar(9, 28, 24155, 24170, 24130, 24165),
                bar(9, 29, 24165, 24180, 24140, 24170),
                bar(9, 30, 24170, 24175, 24150, 24160),
                bar(9, 31, 24160, 24165, 24080, 24100)));

        NiftyTradeLog t = run(params("trailMinutes", "5"), fiveMin, oneMin, closeOf(9, 30), null).tradeLogs().get(0);
        assertThat(t.getFinalStopLoss()).isEqualTo(24090.0);
        assertThat(t.getExitTimestamp()).isEqualTo(at(9, 31));
        assertThat(t.getExitSpotPrice()).isEqualTo(24090.0);
    }

    @Test
    void entryUntilBlocksBreakoutsAtOrAfterTheCutoff() {
        List<Candle> late = List.of(REF,
                bar(13, 55, 24060, 24090, 24050, 24080),
                bar(14, 0, 24080, 24140, 24075, 24135));    // fresh, but starts at 14:00
        assertThat(run(params("entryUntil", "14:00"), late, List.of(), closeOf(14, 0), null).tradeLogs()).isEmpty();

        List<Candle> early = List.of(REF,
                bar(13, 50, 24060, 24090, 24050, 24080),
                bar(13, 55, 24080, 24140, 24075, 24135));   // fresh at 13:55
        assertThat(run(params("entryUntil", "14:00"), early, List.of(), closeOf(13, 55), null).tradeLogs()).hasSize(1);
    }

    @Test
    void maxGapPctSkipsExtremeGapDays() {
        LocalDate d1 = LocalDate.of(2026, 10, 26), d2 = LocalDate.of(2026, 10, 27);
        List<Candle> candles = List.of(
                barOn(d1, 9, 15, 23900, 24000, 23880, 23950),
                barOn(d1, 15, 25, 24000, 24010, 23990, 24000),   // close 24000
                barOn(d2, 9, 15, 24600, 24650, 24580, 24620),    // +2.5% gap
                barOn(d2, 9, 20, 24620, 24700, 24610, 24690));   // CE breakout
        Instant now = d2.atTime(9, 25).atZone(IST).toInstant();

        assertThat(run(params("gapFilter", "GAP_UP", "maxGapPct", "2.0"), candles, List.of(), now, d2).tradeLogs()).isEmpty();
        assertThat(run(params("gapFilter", "GAP_UP", "maxGapPct", "3.0"), candles, List.of(), now, d2).tradeLogs()).hasSize(1);
        assertThat(run(params("gapFilter", "GAP_UP"), candles, List.of(), now, d2).tradeLogs()).hasSize(1);
    }

    // CE entry 24120 at 09:20 (SL 24050); 09:25–09:29 block low 24090, high 24180.
    private static List<Candle> trailFiveMin() {
        return List.of(REF,
                bar(9, 20, 24060, 24130, 24060, 24120),
                bar(9, 25, 24120, 24180, 24090, 24170),
                bar(9, 30, 24170, 24175, 24040, 24060));
    }

    private static List<Candle> trailOneMin() {
        return List.of(
                bar(9, 25, 24120, 24140, 24100, 24130),   // +20
                bar(9, 26, 24130, 24150, 24090, 24145),   // +30
                bar(9, 27, 24145, 24160, 24120, 24155),   // +40
                bar(9, 28, 24155, 24170, 24130, 24165),
                bar(9, 29, 24165, 24180, 24140, 24170),   // block ends: low 24090
                bar(9, 30, 24170, 24175, 24150, 24160),
                bar(9, 31, 24160, 24165, 24080, 24100),   // hits a trailed 24090 stop
                bar(9, 32, 24100, 24110, 24040, 24060));  // hits the untrailed 24050 stop
    }

    @Test
    void trailAfterPointsKeepsTheMidpointStopUntilPriceMovesEnough() {
        NiftyTradeLog t = run(params("trailMinutes", "5", "trailAfterPoints", "100"),
                trailFiveMin(), trailOneMin(), closeOf(9, 30), null).tradeLogs().get(0);
        assertThat(t.getFinalStopLoss()).isEqualTo(24050.0);       // best move was +60: never trailed
        assertThat(t.getExitTimestamp()).isEqualTo(at(9, 32));
        assertThat(t.getExitSpotPrice()).isEqualTo(24050.0);
    }

    @Test
    void trailAfterPointsTrailsNormallyOnceReached() {
        NiftyTradeLog t = run(params("trailMinutes", "5", "trailAfterPoints", "40"),
                trailFiveMin(), trailOneMin(), closeOf(9, 30), null).tradeLogs().get(0);
        assertThat(t.getFinalStopLoss()).isEqualTo(24090.0);       // +40 at 09:27, block low applied at 09:29
        assertThat(t.getExitTimestamp()).isEqualTo(at(9, 31));
    }

    @Test
    void breakevenOnTrailLiftsTheStopToEntryWhenArmed() {
        NiftyTradeLog t = run(params("trailMinutes", "5", "trailAfterPoints", "20", "breakevenOnTrail", "true"),
                trailFiveMin(), trailOneMin(), closeOf(9, 30), null).tradeLogs().get(0);
        assertThat(t.getFinalStopLoss()).isEqualTo(24120.0);       // armed at 09:25 (+20) → stop to entry
        assertThat(t.getExitTimestamp()).isEqualTo(at(9, 26));     // 09:26 low 24090 ≤ 24120
        assertThat(t.getExitSpotPrice()).isEqualTo(24120.0);
    }

    @Test
    void newParametersLeftUnsetKeepTheOriginalBehaviour() {
        NiftyTradeLog t = run(params("trailMinutes", "5"), trailFiveMin(), trailOneMin(), closeOf(9, 30), null)
                .tradeLogs().get(0);
        assertThat(t.getFinalStopLoss()).isEqualTo(24090.0);
        assertThat(t.getExitTimestamp()).isEqualTo(at(9, 31));
    }

    // PE entry: 09:20 closes 23990 below refLow 24000 → entry 23990, midpoint stop 24050.
    private static final Candle PE_BREAKOUT = bar(9, 20, 24060, 24065, 23980, 23990);

    @Test
    void failedBreakoutExitsWhenPeCloseIsBackAboveRefLowPlusPoints() {
        List<Candle> fiveMin = List.of(REF, PE_BREAKOUT,
                bar(9, 25, 23990, 24030, 23985, 24015));   // close 24015 > 24000 + 10, stop 24050 untouched
        NiftyTradeLog t = run(params("directions", "PE", "trailMinutes", "5", "trailAfterPoints", "20",
                "failedBreakoutPoints", "10"), fiveMin, List.of(), closeOf(9, 25), null).tradeLogs().get(0);
        assertThat(t.getExitReason()).isEqualTo("FAILED_BREAKOUT");
        assertThat(t.getExitSpotPrice()).isEqualTo(24015.0);
        assertThat(t.getExitTimestamp()).isEqualTo(at(9, 25));
    }

    @Test
    void failedBreakoutNeedsTheCloseBeyondTheMargin() {
        List<Candle> fiveMin = List.of(REF, PE_BREAKOUT,
                bar(9, 25, 23990, 24030, 23985, 24008),    // 24008 ≤ 24010: stays in
                bar(9, 30, 24008, 24055, 24000, 24040));   // midpoint stop 24050 hit
        NiftyTradeLog t = run(params("directions", "PE", "trailMinutes", "5", "trailAfterPoints", "20",
                "failedBreakoutPoints", "10"), fiveMin, List.of(), closeOf(9, 30), null).tradeLogs().get(0);
        assertThat(t.getExitReason()).isEqualTo("SL_HIT");
        assertThat(t.getExitSpotPrice()).isEqualTo(24050.0);
    }

    @Test
    void failedBreakoutIsIgnoredOnceTheStopIsTrailing() {
        List<Candle> fiveMin = List.of(REF, PE_BREAKOUT,
                bar(9, 25, 23990, 24030, 23965, 24015));   // would be a failed breakout, but armed at 09:25
        List<Candle> oneMin = List.of(
                bar(9, 25, 23990, 23995, 23965, 23970),    // 25 pts in favour → trailing
                bar(9, 26, 23970, 24000, 23968, 23998),
                bar(9, 27, 23998, 24010, 23990, 24005),
                bar(9, 28, 24005, 24020, 24000, 24015),
                bar(9, 29, 24015, 24030, 24010, 24015));   // block high 24030 → stop 24030
        NiftyTradeLog t = run(params("directions", "PE", "trailMinutes", "5", "trailAfterPoints", "20",
                "failedBreakoutPoints", "10"), fiveMin, oneMin, closeOf(9, 25), null).tradeLogs().get(0);
        assertThat(t.getExitReason()).isEqualTo("EOD_SQUAREOFF");   // still open at end of data
        assertThat(t.getFinalStopLoss()).isEqualTo(24030.0);
    }

    @Test
    void failedBreakoutForCeUsesRefHighMinusPoints() {
        List<Candle> fiveMin = List.of(REF,
                bar(9, 20, 24060, 24130, 24060, 24120),    // CE entry 24120, stop 24050
                bar(9, 25, 24120, 24125, 24060, 24085));   // close 24085 < 24100 − 10
        NiftyTradeLog t = run(params("trailMinutes", "5", "trailAfterPoints", "20", "failedBreakoutPoints", "10"),
                fiveMin, List.of(), closeOf(9, 25), null).tradeLogs().get(0);
        assertThat(t.getExitReason()).isEqualTo("FAILED_BREAKOUT");
        assertThat(t.getExitSpotPrice()).isEqualTo(24085.0);
    }

    @Test
    void maxStopPointsUsesTheCloserOfMidpointAndEntryPlusPoints() {
        List<Candle> pe = List.of(REF, PE_BREAKOUT);
        assertThat(run(params("directions", "PE", "maxStopPoints", "40"), pe, List.of(), closeOf(9, 20), null)
                .tradeLogs().get(0).getInitialStopLoss()).isEqualTo(24030.0);   // min(24050, 23990 + 40)
        assertThat(run(params("directions", "PE", "maxStopPoints", "100"), pe, List.of(), closeOf(9, 20), null)
                .tradeLogs().get(0).getInitialStopLoss()).isEqualTo(24050.0);   // midpoint is closer

        List<Candle> ce = List.of(REF, bar(9, 20, 24060, 24130, 24060, 24120));
        assertThat(run(params("maxStopPoints", "40"), ce, List.of(), closeOf(9, 20), null)
                .tradeLogs().get(0).getInitialStopLoss()).isEqualTo(24080.0);   // max(24050, 24120 − 40)
    }

    @Test
    void breakevenLockPointsLocksProfitForCe() {
        NiftyTradeLog t = run(params("trailMinutes", "5", "trailAfterPoints", "20", "breakevenOnTrail", "true",
                "breakevenLockPoints", "5"), trailFiveMin(), trailOneMin(), closeOf(9, 30), null).tradeLogs().get(0);
        assertThat(t.getFinalStopLoss()).isEqualTo(24125.0);       // armed at 09:25 (+20) → entry 24120 + 5
        assertThat(t.getExitTimestamp()).isEqualTo(at(9, 26));     // 09:26 low 24090 ≤ 24125
        assertThat(t.getExitSpotPrice()).isEqualTo(24125.0);
        assertThat(t.getEstimatedPnl()).isEqualTo(5 * 65 * 2 * 0.5);
    }

    @Test
    void breakevenLockPointsLocksProfitForPe() {
        List<Candle> fiveMin = List.of(REF, PE_BREAKOUT, bar(9, 25, 23990, 24030, 23965, 24015));
        List<Candle> oneMin = List.of(
                bar(9, 25, 23990, 23995, 23965, 23970),    // 25 pts in favour → stop to 23990 − 5
                bar(9, 26, 23970, 24000, 23968, 23998));   // high 24000 ≥ 23985 → exit
        NiftyTradeLog t = run(params("directions", "PE", "trailMinutes", "5", "trailAfterPoints", "20",
                "breakevenOnTrail", "true", "breakevenLockPoints", "5"), fiveMin, oneMin, closeOf(9, 25), null)
                .tradeLogs().get(0);
        assertThat(t.getFinalStopLoss()).isEqualTo(23985.0);
        assertThat(t.getExitSpotPrice()).isEqualTo(23985.0);
        assertThat(t.getEstimatedPnl()).isEqualTo(5 * 65 * 2 * 0.5);
    }

    @Test
    void breakevenLockMustBeBelowTheTrigger() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run(params("trailAfterPoints", "20",
                        "breakevenOnTrail", "true", "breakevenLockPoints", "20"),
                        List.of(REF, bar(9, 20, 24060, 24130, 24060, 24120)), List.of(), closeOf(9, 20), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipDaysSkipsTheListedWeekdays() {   // Candles.DAY (27 Oct 2026) is a Tuesday
        List<Candle> candles = List.of(REF, bar(9, 20, 24060, 24130, 24060, 24120));
        assertThat(run(params("skipDays", "TUE"), candles, List.of(), closeOf(9, 20), null).tradeLogs()).isEmpty();
        assertThat(run(params("skipDays", "mon, tuesday"), candles, List.of(), closeOf(9, 20), null).tradeLogs()).isEmpty();
        assertThat(run(params("skipDays", "THU"), candles, List.of(), closeOf(9, 20), null).tradeLogs()).hasSize(1);
        assertThat(run(params("skipDays", ""), candles, List.of(), closeOf(9, 20), null).tradeLogs()).hasSize(1);
    }

    @Test
    void skippedDayStillSuppliesThePreviousCloseForTheNextGap() {
        LocalDate mon = LocalDate.of(2026, 10, 26), tue = LocalDate.of(2026, 10, 27), wed = LocalDate.of(2026, 10, 28);
        List<Candle> candles = List.of(
                barOn(mon, 9, 15, 23900, 24000, 23880, 23950),
                barOn(mon, 15, 25, 24000, 24010, 23990, 24000),   // Mon close 24000
                barOn(tue, 9, 15, 24100, 24150, 24080, 24120),    // Tue: skipped
                barOn(tue, 15, 25, 24600, 24610, 24590, 24600),   // Tue close 24600
                barOn(wed, 9, 15, 24650, 24700, 24640, 24660),    // +0.20% vs Tue (+2.7% vs Mon)
                barOn(wed, 9, 20, 24660, 24750, 24655, 24740));   // CE breakout
        Instant now = wed.atTime(9, 25).atZone(IST).toInstant();
        List<NiftyTradeLog> trades = run(params("gapFilter", "GAP_UP", "gapPct", "0.2", "maxGapPct", "2.0", "skipDays", "TUE"),
                candles, List.of(), now, tue).tradeLogs();
        assertThat(trades).extracting(NiftyTradeLog::getTradeDate).containsExactly(wed);
        assertThat(trades.get(0).getGapPercent()).isCloseTo(0.2033, org.assertj.core.api.Assertions.within(1e-3));
    }

    @Test
    void skipDaysRejectsUnknownNames() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run(params("skipDays", "THU,XYZ"),
                        List.of(REF), List.of(), closeOf(9, 15), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("XYZ");
    }

    @Test
    void dayReportsExplainEveryDay() {
        LocalDate mon = LocalDate.of(2026, 10, 26), tue = LocalDate.of(2026, 10, 27), wed = LocalDate.of(2026, 10, 28),
                  thu = LocalDate.of(2026, 10, 29), fri = LocalDate.of(2026, 10, 30);
        List<Candle> c = List.of(
                barOn(mon, 9, 15, 23900, 24000, 23880, 23950), barOn(mon, 15, 25, 24000, 24010, 23990, 24000),
                barOn(tue, 9, 15, 24100, 24150, 24080, 24120), barOn(tue, 9, 20, 24120, 24200, 24110, 24190),   // +0.42%: trades
                barOn(tue, 15, 25, 24050, 24060, 24040, 24050),
                barOn(wed, 9, 15, 24060, 24100, 24040, 24070), barOn(wed, 15, 25, 24060, 24070, 24050, 24060), // flat
                barOn(thu, 9, 15, 24200, 24220, 24190, 24210), barOn(thu, 15, 25, 24210, 24220, 24200, 24210), // skipped
                barOn(fri, 9, 20, 24300, 24310, 24290, 24300));                                              // no 09:15
        Instant now = fri.atTime(15, 30).atZone(IST).toInstant();
        List<NiftyOpeningBreakoutEngine.DayReport> days = run(params("directions", "CE", "gapFilter", "GAP_UP",
                "gapPct", "0.2", "skipDays", "THU"), c, List.of(), now, tue).days();

        assertThat(days).extracting(NiftyOpeningBreakoutEngine.DayReport::status)
                .containsExactly("TRADED", "NO_TRADE", "SKIPPED", "NO_DATA");
        assertThat(days.get(0).trades()).isEqualTo(1);
        assertThat(days.get(1).reason()).contains("does not meet GAP_UP");
        assertThat(days.get(2).reason()).isEqualTo("skipDays includes THURSDAY");
        assertThat(days.get(2).gapPercent()).isNotNull();
    }

    @Test
    void runLiveKeepsATradeOpenAtTheEndOfTheDataWithItsCurrentStop() {
        List<Candle> fiveMin = List.of(REF, bar(9, 20, 24060, 24130, 24060, 24120), bar(9, 25, 24120, 24140, 24100, 24130));
        NiftyOpeningBreakoutEngine.BreakoutResult live = engine.runLive(new PriceSeries("NIFTY 50", Interval.MINUTE_5, fiveMin),
                new PriceSeries("NIFTY 50", Interval.MINUTE_1, List.of()), params, 1L, closeOf(9, 25), null);
        assertThat(live.tradeLogs()).isEmpty();
        assertThat(live.openTrades()).hasSize(1);
        assertThat(live.openTrades().get(0).getFinalStopLoss()).isEqualTo(24050.0);
        assertThat(live.openTrades().get(0).getExitReason()).isNull();

        NiftyOpeningBreakoutEngine.BreakoutResult backtest = run(fiveMin, List.of(), closeOf(9, 25));
        assertThat(backtest.openTrades()).isEmpty();
        assertThat(backtest.tradeLogs()).extracting(NiftyTradeLog::getExitReason).containsExactly("EOD_SQUAREOFF");
    }

    // 10:45 reference candle: high 24,372, low 24,345 (Candles.DAY is Tue 27 Oct 2026).
    private static final Candle REF_1045 = bar(10, 45, 24360, 24372, 24345, 24369);

    @Test
    void referenceTimeBreakdownBuysPeWithTheReferenceHighAsStopAndTrailsOneMinuteHighs() {
        List<Candle> five = List.of(REF, REF_1045,
                bar(10, 50, 24369, 24370, 24340, 24341),       // closes below 24,345 → PE (ATM 24,350 + 50)
                bar(10, 55, 24341, 24350, 24330, 24345));
        List<Candle> one = List.of(
                bar(10, 55, 24341, 24350, 24335, 24340),       // stop → min(24,372, 24,350)
                bar(10, 56, 24340, 24346, 24330, 24333),       // → 24,346
                bar(10, 57, 24333, 24347, 24331, 24345));      // high 24,347 ≥ 24,346 → stop hit
        NiftyTradeLog t = run(params("referenceTime", "10:45", "initialStop", "OPPOSITE", "trailMinutes", "1",
                "maxTradesPerDay", "1"), five, one, closeOf(10, 55), null).tradeLogs().get(0);
        assertThat(t.getOptionType()).isEqualTo("PE");
        assertThat(t.getStrikePrice()).isEqualTo(24400);
        assertThat(t.getReferenceHigh()).isEqualTo(24372.0);
        assertThat(t.getInitialStopLoss()).isEqualTo(24372.0);         // reference high, not the midpoint
        assertThat(t.getFinalStopLoss()).isEqualTo(24346.0);
        assertThat(t.getExitSpotPrice()).isEqualTo(24346.0);
        assertThat(t.getExitTimestamp()).isEqualTo(at(10, 57));
    }

    @Test
    void referenceTimeBreakoutBuysCeWithTheReferenceLowAsStop() {
        List<Candle> five = List.of(REF, REF_1045,
                bar(10, 50, 24369, 24380, 24360, 24378));       // closes above 24,372 → CE (ATM 24,400 − 50)
        NiftyTradeLog t = run(params("referenceTime", "10:45", "initialStop", "OPPOSITE", "trailMinutes", "1"),
                five, List.of(), closeOf(10, 50), null).tradeLogs().get(0);
        assertThat(t.getOptionType()).isEqualTo("CE");
        assertThat(t.getStrikePrice()).isEqualTo(24350);
        assertThat(t.getInitialStopLoss()).isEqualTo(24345.0);         // reference low
    }

    @Test
    void breakoutsBeforeTheReferenceCandleAreIgnoredAndOnlyOnLimitsTheDay() {
        List<Candle> five = List.of(REF, bar(9, 20, 24060, 24400, 24060, 24390), REF_1045,
                bar(10, 50, 24369, 24372, 24350, 24360));       // inside the 10:45 range: no trade
        assertThat(run(params("referenceTime", "10:45", "initialStop", "OPPOSITE"), five, List.of(), closeOf(10, 50), null)
                .tradeLogs()).isEmpty();

        List<Candle> breakout = List.of(REF, REF_1045, bar(10, 50, 24369, 24380, 24360, 24378));
        assertThat(run(params("referenceTime", "10:45", "onlyOn", "2026-10-28"), breakout, List.of(), closeOf(10, 50), null)
                .tradeLogs()).isEmpty();
        assertThat(run(params("referenceTime", "10:45", "onlyOn", "2026-10-27"), breakout, List.of(), closeOf(10, 50), null)
                .tradeLogs()).hasSize(1);
    }
}
