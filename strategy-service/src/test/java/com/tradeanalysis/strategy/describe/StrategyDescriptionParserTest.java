package com.tradeanalysis.strategy.describe;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyDescriptionParserTest {

    private final StrategyDescriptionParser parser = new StrategyDescriptionParser();

    private StrategyDescriptionParser.Result parse(String d) { return parser.parse(d); }

    @Test
    void freeFormCeGapUpStrategy() {
        var r = parse("CE only on gap-up days above 0.2%, 3 lots, lot size 65, max 1 trade per day. "
                + "Trail SL on 5-min candle lows, target 80 points, no entry after 14:00, skip Thursdays, charges Rs 85 per trade.");
        assertThat(r.type()).isEqualTo("NIFTY_OPENING_BREAKOUT");
        assertThat(r.parameters()).containsAllEntriesOf(Map.of(
                "directions", "CE", "gapFilter", "GAP_UP", "gapPct", "0.2", "lots", "3",
                "maxTradesPerDay", "1", "trailMinutes", "5", "targetPoints", "80", "entryUntil", "14:00",
                "skipDays", "THU"));
        assertThat(r.parameters()).containsEntry("costPerTrade", "85").doesNotContainKey("lotSize");
        assertThat(r.warnings()).isEmpty();
        assertThat(r.notUnderstood()).isEmpty();
        assertThat(r.suggestedName()).isEqualTo("Nifty CE Gap-Up Breakout 80 Target - 3 lots");
        assertThat(r.findings()).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("entryUntil");
            assertThat(f.text()).isEqualTo("no entry after 14:00");
        });
    }

    @Test
    void peGapDownWithStopRules() {
        var r = parse("PE only when NIFTY gaps down by 0.3%. 2 lots. Trail after 20 points, breakeven on trail, lock 5 points. "
                + "Failed breakdown exit 10 points, 40pt stop cap. Skip gaps above 2%. Entries from 10:00 until 2 pm.");
        assertThat(r.parameters()).containsAllEntriesOf(Map.of(
                "directions", "PE", "gapFilter", "GAP_DOWN", "gapPct", "0.3", "lots", "2", "trailAfterPoints", "20",
                "breakevenOnTrail", "true", "breakevenLockPoints", "5", "failedBreakoutPoints", "10",
                "maxStopPoints", "40", "maxGapPct", "2"));
        assertThat(r.parameters()).containsEntry("entryFrom", "10:00").containsEntry("entryUntil", "14:00");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void originalStrategy34DescriptionReadsBuiltInRules() {
        var r = parse("Nifty 50 first 5-min candle breakout. Ref candle 09:15-09:20 IST. Bullish close > refHigh: buy CE at ATM-50. "
                + "Bearish close < refLow: buy PE at ATM+50. ATM rounded to nearest 50. EOD square-off 15:20 IST. "
                + "Max 1 open position. Max 2 trades/day. Fresh breakout only.");
        assertThat(r.type()).isEqualTo("NIFTY_OPENING_BREAKOUT");
        assertThat(r.parameters()).containsEntry("maxTradesPerDay", "2").doesNotContainKey("directions");   // both sides = default
        assertThat(r.findings()).anySatisfy(f -> assertThat(f.note()).contains("Square-off at 15:20"));
        assertThat(r.findings()).anySatisfy(f -> assertThat(f.note()).contains("One open position"));
    }

    @Test
    void outOfRangeValuesAreWarningsNotParameters() {
        var r = parse("CE only, 500 lots, target 5000 points, no entry after 18:00");
        assertThat(r.parameters()).containsEntry("directions", "CE")
                .doesNotContainKeys("lots", "targetPoints", "entryUntil");
        assertThat(r.warnings()).hasSize(3);
        assertThat(String.join(" ", r.warnings())).contains("lots 500", "targetPoints 5000", "market hours");
    }

    @Test
    void unknownSentencesAreReturnedForTheUserToCheck() {
        var r = parse("CE only, 2 lots. Use RSI divergence from the hourly chart to confirm.");
        // "RSI" switches the type: the description mixes ideas the parser can't combine, so it is flagged
        assertThat(r.type()).isEqualTo("RSI_THRESHOLD");
        var b = parse("CE only, 2 lots. Only trade when VIX is calm and FII flows are positive.");
        assertThat(b.notUnderstood()).containsExactly("Only trade when VIX is calm and FII flows are positive");
    }

    @Test
    void conflictingValuesWarnAndKeepTheLastOne() {
        var r = parse("2 lots on Monday, 4 lots otherwise, CE only");
        assertThat(r.parameters()).containsEntry("lots", "4");
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).contains("lots was described twice"));
    }

    @Test
    void gapSizesInPoints() {
        var up = parse("CE only, gap up above 50 points, skip gaps above 300 points, 2 lots");
        assertThat(up.parameters()).containsEntry("gapFilter", "GAP_UP").containsEntry("gapPoints", "50")
                .containsEntry("maxGapPoints", "300").doesNotContainKeys("gapPct", "maxGapPct");
        var opens = parse("PE only when nifty opens 40 pts below the previous close");
        assertThat(opens.parameters()).containsEntry("gapFilter", "GAP_DOWN").containsEntry("gapPoints", "40");
        var mixed = parse("Gap up above 0.3%, gap < 250 points");
        assertThat(mixed.parameters()).containsEntry("gapPct", "0.3").containsEntry("maxGapPoints", "250");
    }

    @Test
    void opensAbovePreviousCloseIsAGapUp() {
        var r = parse("Buy calls only when nifty opens 0.25% above the previous close, 1 lot");
        assertThat(r.parameters()).containsEntry("gapFilter", "GAP_UP").containsEntry("gapPct", "0.25")
                .containsEntry("directions", "CE").containsEntry("lots", "1");
    }

    @Test
    void indicatorStrategies() {
        assertThat(parse("10/30 SMA crossover").parameters()).containsEntry("fast", "10").containsEntry("slow", "30");
        assertThat(parse("SMA crossover fast 10 slow 30").type()).isEqualTo("SMA_CROSSOVER");
        var rsi = parse("RSI 14, buy when oversold 25 recovers, sell overbought 75");
        assertThat(rsi.type()).isEqualTo("RSI_THRESHOLD");
        assertThat(rsi.parameters()).containsEntry("period", "14").containsEntry("oversold", "25").containsEntry("overbought", "75");
        assertThat(parse("MACD 12,26,9 signal crossover").parameters())
                .containsEntry("fast", "12").containsEntry("slow", "26").containsEntry("signal", "9");
        assertThat(parse("30/10 SMA crossover").warnings()).anySatisfy(w -> assertThat(w).contains("fast SMA should be shorter"));
    }

    @Test
    void emptyDescription() {
        var r = parse("  ");
        assertThat(r.parameters()).isEmpty();
        assertThat(r.typeDetected()).isFalse();
        assertThat(r.warnings()).containsExactly("Describe the strategy first");
    }

    @Test
    void masterStrategyNamesParse() {
        var ce = parse("Nifty CE Gap-Up Breakout 80 Target - delayed trail, no entry after 14:00, gap < 2% - skip THU");
        assertThat(ce.parameters()).containsAllEntriesOf(Map.of("directions", "CE", "gapFilter", "GAP_UP",
                "targetPoints", "80", "entryUntil", "14:00", "maxGapPct", "2", "skipDays", "THU"));
        assertThat(ce.findings()).anySatisfy(f -> assertThat(f.note()).contains("trail after N points"));
    }

    /** Text the UI's diagram reader produced (real Tesseract OCR) from a flowchart: one box per line. */
    @Test
    void flowDiagramInstructionsOnePerLine() {
        var r = parse("Gap up above 0.2%.\nBuy CE only, 3 lots 1 trade per day.\nTrail on 5-min lows.\nTarget 80 points.\nNo entry after 14:00.");
        assertThat(r.type()).isEqualTo("NIFTY_OPENING_BREAKOUT");
        assertThat(r.parameters()).containsAllEntriesOf(Map.of(
                "directions", "CE", "gapFilter", "GAP_UP", "gapPct", "0.2", "lots", "3", "maxTradesPerDay", "1",
                "trailMinutes", "5", "targetPoints", "80", "entryUntil", "14:00"));
        assertThat(r.warnings()).isEmpty();
        assertThat(r.notUnderstood()).isEmpty();
    }

    static final String STEP_BY_STEP = """
            Here's the 9:20 strategy step by step:
            Step 1 — Market opens (9:15) Wait for the first 5-min candle to form. Note its low — this will be your initial SL.
            Step 2 — Check the 9:20 candle gapAt 9:20, see if the candle has gapped up. There must be a minimum gap up to proceed.
            Step 3 — Is the gap too big?If the gap is very large → skip the trade entirely (not applicable). Only proceed if it's a minimum/modest gap.
            Step 4 — Observe the 9:25 candleWatch if the 9:25 candle barely crosses above the 9:20 candle. A strong breakout doesn't qualify — it should be a marginal cross.
            If it doesn't cross → no trade.
            Step 5 — Check resistance / supportIs the price sitting near a key resistance or support level?
            If yes → avoid the trade.
            If no → conditions are met.
            Step 6 — Enter the trade (buy)All 3 conditions passed. Enter long.
            Step 7 — Set initial SL Place stop loss at the low of the first 5-min candle (9:15 candle low).
            Step 8 — Trail the SLAfter every new 5-min candle closes, move your SL up to the low of that just-closed candle. Repeat this until you hit target or get stopped out.
            """;

    /** A step-by-step write-up (pasted from a web page, so some words are glued together). */
    @Test
    void stepByStepWriteUp() {
        var r = parse(STEP_BY_STEP);
        assertThat(r.type()).isEqualTo("NIFTY_OPENING_BREAKOUT");
        assertThat(r.parameters()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "initialStop", "OPPOSITE", "directions", "CE", "trailMinutes", "5", "gapFilter", "GAP_UP"));
        assertThat(r.notUnderstood()).isEmpty();
        // Rules the engine cannot follow are warnings, once each, not "not understood".
        assertThat(r.warnings()).hasSize(3)
                .anySatisfy(w -> assertThat(w).startsWith("Breakout size is not checked"))
                .anySatisfy(w -> assertThat(w).startsWith("Support/resistance is not part"))
                .anySatisfy(w -> assertThat(w).startsWith("Large-gap skip needs a number"));
        assertThat(r.findings()).anySatisfy(f -> assertThat(f.note()).contains("Gap % keeps its default 0.2%"));
    }

    @Test
    void muhuratSessionReferenceCandleAndDate() {
        var r = parse("CE only. Reference candle at 14:20. Entries from 14:25 until 15:10. Max 2 trades per day. "
                + "SL at the low of the first candle. Max SL 20 points. Target 35 points. Trail on 1-min lows. "
                + "Trail after 20 points in profit. 1 lot. Only on 2026-11-08.");
        assertThat(r.parameters()).containsEntry("referenceTime", "14:20").containsEntry("onlyOn", "2026-11-08")
                .containsEntry("entryFrom", "14:25").containsEntry("entryUntil", "15:10")
                .containsEntry("initialStop", "OPPOSITE").containsEntry("directions", "CE")
                .containsEntry("maxTradesPerDay", "2").containsEntry("maxStopPoints", "20")
                .containsEntry("targetPoints", "35").containsEntry("trailMinutes", "1")
                .containsEntry("trailAfterPoints", "20").containsEntry("lots", "1");
        assertThat(r.notUnderstood()).isEmpty();
        assertThat(r.warnings()).isEmpty();
        assertThat(parse("CE only. First candle at 2:20 pm. Trade only on 8 Nov 2026.").parameters())
                .containsEntry("referenceTime", "14:20").containsEntry("onlyOn", "2026-11-08");
    }

    @Test
    void bankNiftyPreviousDayBreakdownWithAStopCap() {
        var r = parse("Bank Nifty PE Breakdown 50 Target - stop cap 150 - 2 lots. PE only, on days that open inside yesterday's high-low range. "
                + "Enter PE when a 5-minute candle closes below yesterday's low (fresh cross, from the 09:20 candle), no entry after 14:00, "
                + "1 trade per day, skip Thursdays. Stop at yesterday's high, but never more than 150 points above entry. Target 50 points. "
                + "Square-off at 15:20. 2 lots, lot size 65, charges Rs 85 per trade.");
        assertThat(r.parameters()).containsAllEntriesOf(Map.of(
                "underlying", "BANKNIFTY", "referenceMode", "PREVIOUS_DAY", "directions", "PE", "maxStopPoints", "150",
                "targetPoints", "50", "entryUntil", "14:00", "maxTradesPerDay", "1", "skipDays", "THU", "lots", "2", "costPerTrade", "85"));
        assertThat(r.warnings()).singleElement().asString().contains("Bank Nifty uses 30");   // lot size 65 is NIFTY's
        assertThat(r.notUnderstood()).isEmpty();
        assertThat(r.suggestedName()).isEqualTo("Bank Nifty PE Prev-Day Breakdown 50 Target - stop cap 150 - 2 lots");
    }

    @Test
    void previousDayCeOnNiftyWithoutACap() {
        var r = parse("CE only when the day opens inside the previous day's range. Buy CE on a close above yesterday's high. "
                + "Stop at yesterday's low. Target 60 points. 1 trade per day.");
        assertThat(r.parameters()).containsAllEntriesOf(Map.of("referenceMode", "PREVIOUS_DAY", "directions", "CE", "targetPoints", "60"))
                .doesNotContainKeys("maxStopPoints", "underlying");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void fifteenMinuteGapDirectionStrategyWithEveryNewSetting() {
        var r = parse("Trades only when NIFTY opens >= 0.2% and < 1% away from the previous close, in the direction of the 20-day trend: "
                + "gap up -> CE only if the previous close is above its 20-session average; gap down -> PE only if below. "
                + "Reference = first 15-min candle 09:15-09:30 IST. Entry on a fresh 5-min close beyond the 15-min high/low, on candles "
                + "starting before 13:00, only if the breakout candle closes in its strong half. Initial SL = breakout candle low (CE) / high (PE). "
                + "Max 1 trade/day. EOD square-off 15:20. Rs 85 charges per trade. No target: at 1R the stop moves to entry, then trails on "
                + "completed 5-min lows (CE) / highs (PE).");
        assertThat(r.parameters()).containsAllEntriesOf(Map.of(
                "referenceMinutes", "15", "directions", "WITH_GAP", "trendSma", "20", "breakoutStrength", "0.5",
                "initialStop", "BREAKOUT_CANDLE", "trailAfterR", "1", "breakevenOnTrail", "true", "trailMinutes", "5",
                "maxTradesPerDay", "1", "costPerTrade", "85"));
        assertThat(r.parameters()).containsEntry("targetPoints", "0").containsEntry("entryUntil", "13:00")
                .containsEntry("gapFilter", "ANY_GAP").containsEntry("gapPct", "0.2").containsEntry("maxGapPct", "1");
        assertThat(r.warnings()).isEmpty();
        assertThat(r.suggestedName()).startsWith("Nifty CE/PE");
    }

    @Test
    void targetsAndTrailsInMultiplesOfRisk() {
        assertThat(parse("CE only. Target 2R.").parameters()).containsEntry("targetR", "2").doesNotContainKey("targetPoints");
        assertThat(parse("CE only. Risk reward 1:2.5").parameters()).containsEntry("targetR", "2.5");
        assertThat(parse("PE only. 1:2 target").parameters()).containsEntry("targetR", "2");
        assertThat(parse("PE only. Target at 1.5x the risk").parameters()).containsEntry("targetR", "1.5");
        assertThat(parse("CE only. Trail after 1.5R in profit.").parameters()).containsEntry("trailAfterR", "1.5");
        assertThat(parse("CE only. No new entries after 1:30 pm").parameters()).containsEntry("entryUntil", "13:30").doesNotContainKey("targetR");
    }

    @Test
    void breakoutStrengthAndReferenceRanges() {
        assertThat(parse("CE only, breakout candle closes in the top 30% of its range").parameters()).containsEntry("breakoutStrength", "0.7");
        assertThat(parse("CE only, 30-minute opening range").parameters()).containsEntry("referenceMinutes", "30");
        assertThat(parse("PE only, reference candle 09:15-09:45").parameters()).containsEntry("referenceMinutes", "30");
        assertThat(parse("CE only. Reference candle 09:15-09:20 IST.").parameters()).doesNotContainKey("referenceMinutes");
        var bad = parse("CE only, first 7-min candle");
        assertThat(bad.parameters()).doesNotContainKey("referenceMinutes");
        assertThat(bad.warnings()).anySatisfy(w -> assertThat(w).contains("multiple of 5"));
        assertThat(parse("Bank Nifty CE only, trail after 300 points, target 600 points, stop cap 900 points").parameters())
                .containsAllEntriesOf(Map.of("trailAfterPoints", "300", "targetPoints", "600", "maxStopPoints", "900"));
    }

    @Test
    void aStopCapNextToAGapFilterIsNotReadAsAGapSize() {
        var r = parse("CE only on gap-up days above 0.1%, skip gaps above 2%. Initial SL at the midpoint of the reference candle, "
                + "but never more than 60 points below entry. Trail only after +30 points in profit: move SL to breakeven, then trail on completed 10-min lows.");
        assertThat(r.parameters()).containsAllEntriesOf(Map.of("gapFilter", "GAP_UP", "gapPct", "0.1", "maxGapPct", "2",
                "maxStopPoints", "60", "initialStop", "MIDPOINT", "trailAfterPoints", "30", "trailMinutes", "10"))
                .doesNotContainKey("gapPoints");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void bankNiftyGapFillOnGapUpDays() {
        var r = parse("Bank Nifty PE only on gap-up days above 0.2%, skip gaps above 2%. Once the price falls back below today's open "
                + "(a 5-min close back into the gap), buy PE. Target half the gap. Stop at the 09:15 candle's high. "
                + "No new entries after 14:00. 1 trade per day. Skip Thursday. 2 lots. Charges Rs 85 per trade.");
        assertThat(r.parameters()).containsAllEntriesOf(Map.of("underlying", "BANKNIFTY", "referenceMode", "GAP_FILL", "directions", "PE",
                "gapPct", "0.2", "maxGapPct", "2", "gapFillTarget", "0.5", "entryUntil", "14:00", "maxTradesPerDay", "1", "skipDays", "THU"))
                .doesNotContainKeys("gapFilter", "initialStop", "targetPoints");
        assertThat(r.warnings()).isEmpty();
        assertThat(r.suggestedName()).isEqualTo("Bank Nifty PE Gap-Up Fill 50% - 2 lots");
    }

    @Test
    void gapFillTargetsAndSidesFromTheGap() {
        assertThat(parse("Gap fill on gap-down days above 0.3%: CE back into the gap, target the full gap fill, max stop distance 100 points.").parameters())
                .containsAllEntriesOf(Map.of("referenceMode", "GAP_FILL", "directions", "CE", "gapPct", "0.3", "gapFillTarget", "1", "maxStopPoints", "100"));
        assertThat(parse("Gap fill both ways, target 75% of the gap").parameters())
                .containsEntry("gapFillTarget", "0.75").doesNotContainKey("targetPoints");
        assertThat(parse("CE only, target 50 points").parameters()).doesNotContainKey("referenceMode");   // no gap-fill words
    }

    @Test
    void nr7AndNr4PreviousDayBreakouts() {
        var nr7 = parse("Nifty, both CE and PE, after an NR7 day (yesterday's high-low range was the smallest of the last 7 sessions), "
                + "on days that open inside yesterday's high-low range. Enter CE when a 5-minute candle closes above yesterday's high, "
                + "PE when it closes below yesterday's low (fresh cross, from the 09:20 candle). No new entries after 14:00. 1 trade per day. "
                + "Stop at yesterday's low (CE) / high (PE), but never more than 30 points from entry. Target 80 points. "
                + "Square-off at 15:20. 2 lots. Charges Rs 85 per trade.");
        assertThat(nr7.parameters()).containsAllEntriesOf(Map.of("referenceMode", "PREVIOUS_DAY", "directions", "BOTH",
                "narrowRangeDays", "7", "maxStopPoints", "30", "targetPoints", "80", "lots", "2"));
        assertThat(nr7.warnings()).isEmpty();
        assertThat(nr7.notUnderstood()).isEmpty();
        assertThat(nr7.suggestedName()).isEqualTo("Nifty CE/PE Prev-Day NR7 Breakout 80 Target - stop cap 30 - 2 lots");

        var nr4 = parse("Nifty, both CE and PE, after an NR4 day but not an NR7 day, on days that open inside yesterday's high-low range. "
                + "Stop at yesterday's low (CE) / high (PE), but never more than 30 points from entry. Target 50 points.");
        assertThat(nr4.parameters()).containsAllEntriesOf(Map.of("narrowRangeDays", "4", "excludeNarrowRangeDays", "7"));
        assertThat(nr4.warnings()).isEmpty();
        // Both sides named on a previous-day strategy: BOTH, not the engine's PE default.
        assertThat(parse("CE above yesterday's high, PE below yesterday's low").parameters()).containsEntry("directions", "BOTH");
    }

    @Test
    void narrowCprBreakout() {
        var r = parse("Nifty narrow-CPR breakout, both CE and PE. Pivot levels from the previous session; trade only when the CPR is "
                + "narrower than 0.15% and the day opens between S1 and R1. Enter CE when a 5-minute candle closes above R1, PE when it "
                + "closes below S1 (fresh cross). No new entries after 14:00. 1 trade per day. Stop at the pivot, but never more than "
                + "40 points from entry. Target 80 points. Square-off at 15:20. 2 lots. Charges Rs 85 per trade.");
        assertThat(r.parameters()).containsAllEntriesOf(Map.of("referenceMode", "CPR", "directions", "BOTH", "maxCprWidthPct", "0.15",
                "maxStopPoints", "40", "targetPoints", "80", "entryUntil", "14:00"));
        assertThat(r.warnings()).isEmpty();
        assertThat(r.notUnderstood()).isEmpty();
        assertThat(r.suggestedName()).isEqualTo("Nifty CE/PE Narrow-CPR Breakout 80 Target - stop cap 40 - 2 lots");
    }

    @Test
    void emaCrossoverBuysOptionsAndReadsAStartTime() {
        var r = parse("Nifty 5-min EMA 9/21 crossover, both CE and PE. Buy CE when the 9 EMA crosses above the 21 EMA, PE when it crosses "
                + "below. No entries before 09:45 and no new entries after 14:00. 1 trade per day. Stop at the low (CE) / high (PE) of the "
                + "last 3 candles, but never more than 40 points from entry. Target 80 points. Square-off at 15:20. 2 lots. "
                + "Charges Rs 85 per trade.");
        assertThat(r.type()).isEqualTo("NIFTY_OPENING_BREAKOUT");
        assertThat(r.parameters()).containsAllEntriesOf(Map.of("referenceMode", "EMA_CROSS", "fastEma", "9", "slowEma", "21",
                "swingBars", "3", "entryFrom", "09:45", "entryUntil", "14:00", "maxStopPoints", "40", "targetPoints", "80"));
        assertThat(r.warnings()).isEmpty();
        assertThat(r.notUnderstood()).isEmpty();
        assertThat(r.suggestedName()).isEqualTo("Nifty CE/PE EMA 9/21 Cross 80 Target - stop cap 40 - 2 lots");
        // "No entries before" is a start time on the opening breakout too, not a cut-off.
        assertThat(parse("CE only, no entries before 09:30").parameters()).containsEntry("entryFrom", "09:30").doesNotContainKey("entryUntil");
        // The equity SMA strategy is untouched.
        assertThat(parse("10/30 SMA crossover").type()).isEqualTo("SMA_CROSSOVER");
    }
}
