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
                "directions", "CE", "gapFilter", "GAP_UP", "gapPct", "0.2", "lots", "3", "lotSize", "65",
                "maxTradesPerDay", "1", "trailMinutes", "5", "targetPoints", "80", "entryUntil", "14:00",
                "skipDays", "THU"));
        assertThat(r.parameters()).containsEntry("costPerTrade", "85");
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
}
