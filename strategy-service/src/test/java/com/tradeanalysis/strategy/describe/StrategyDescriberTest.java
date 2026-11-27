package com.tradeanalysis.strategy.describe;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** A description written by StrategyDescriber reads back to exactly the same parameters. */
class StrategyDescriberTest {

    private final StrategyDescriptionParser parser = new StrategyDescriptionParser();

    private static Map<String, String> m(String... kv) {
        Map<String, String> p = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put(kv[i], kv[i + 1]);
        return p;
    }

    private void roundTrip(String type, Map<String, String> p) {
        String d = StrategyDescriber.describe(type, p);
        var r = parser.parse(d);
        assertThat(r.type()).as(d).isEqualTo(type);
        assertThat(r.parameters()).as(d).isEqualTo(p);
        assertThat(r.warnings()).as(d).isEmpty();
    }

    static final String B = "NIFTY_OPENING_BREAKOUT";

    @Test
    void openingRangeStrategies() {
        roundTrip(B, m("gapPct", "0.1", "initialStop", "OPPOSITE", "maxStopPoints", "100", "entryUntil", "14:00", "underlying", "NIFTY",
                "gapFilter", "NOT_GAP_DOWN", "referenceMinutes", "5", "maxTradesPerDay", "1", "breakevenLockPoints", "10", "skipDays", "MON",
                "lots", "4", "breakevenOnTrail", "true", "skipMonthEndExpiry", "true", "trailAfterPoints", "40", "directions", "CE",
                "trailMinutes", "60", "costPerTrade", "85"));
        roundTrip(B, m("gapPct", "0.1", "initialStop", "OPPOSITE", "maxStopPoints", "100", "entryUntil", "11:00", "underlying", "NIFTY",
                "gapFilter", "FLAT", "referenceMinutes", "15", "maxTradesPerDay", "1", "breakevenLockPoints", "10", "breakevenOnTrail", "true",
                "lots", "4", "skipMonthEndExpiry", "true", "trailAfterPoints", "40", "directions", "BOTH", "trailMinutes", "60", "costPerTrade", "85"));
        roundTrip(B, m("initialStop", "OPPOSITE", "maxStopPoints", "250", "entryUntil", "14:00", "targetPoints", "250", "underlying", "BANKNIFTY",
                "referenceMinutes", "5", "maxTradesPerDay", "1", "breakevenLockPoints", "25", "skipDays", "WED", "trendSma", "20",
                "breakevenOnTrail", "true", "lots", "1", "trailAfterPoints", "100", "directions", "CE", "trailMinutes", "60", "costPerTrade", "85"));
        roundTrip(B, m("directions", "CE", "gapFilter", "GAP_UP", "gapPct", "0.2", "targetPoints", "100", "maxStopPoints", "60", "lots", "4",
                "referenceTime", "09:15", "trailAfterPoints", "20", "skipMonthEndExpiry", "true"));
        roundTrip(B, m("directions", "PE", "gapFilter", "GAP_DOWN", "gapPct", "0.1", "targetPoints", "80", "maxStopPoints", "40", "lots", "4"));
        roundTrip(B, m("referenceTime", "10:15", "referenceMinutes", "15", "targetPoints", "40", "failedBreakoutPoints", "10",
                "gapFilter", "GAP_DOWN", "maxGapPct", "2", "skipDays", "THU,TUE", "initialStop", "MIDPOINT", "entryFrom", "10:00"));
        roundTrip(B, m("directions", "PE", "narrowRangeDays", "4", "targetPoints", "40", "maxStopPoints", "100", "trailMinutes", "0",
                "breakevenOnTrail", "true", "underlying", "NIFTY"));
        roundTrip(B, m("underlying", "BANKNIFTY", "directions", "PE", "referenceMinutes", "15", "initialStop", "OPPOSITE", "maxStopPoints", "250",
                "targetPoints", "200", "entryUntil", "14:00", "trailMinutes", "5", "trailAfterPoints", "1000", "maxTradesPerDay", "1",
                "onlyMonthEndExpiry", "true", "lots", "1", "costPerTrade", "85"));
        roundTrip(B, m("directions", "WITH_GAP", "gapFilter", "ANY_GAP", "gapPct", "0.3", "targetPoints", "0", "slippagePoints", "2"));
    }

    @Test
    void cprStrategies() {
        roundTrip(B, m("lots", "4", "skipMonthEndExpiry", "true", "maxStopPoints", "60", "referenceMode", "CPR", "entryUntil", "12:30",
                "maxCprWidthPct", "0.2", "costPerTrade", "85", "targetPoints", "100"));
        roundTrip(B, m("referenceMode", "CPR", "directions", "BOTH", "minCprWidthPct", "0.15", "maxCprWidthPct", "0.3", "maxStopPoints", "100",
                "breakevenOnTrail", "true", "trailAfterPoints", "30", "trailMinutes", "0", "skipDays", "TUE"));
        roundTrip(B, m("referenceMode", "CPR", "directions", "CE", "minCprWidthPct", "0.1", "narrowRangeDays", "4", "excludeNarrowRangeDays", "7"));
        roundTrip(B, m("referenceMode", "CPR", "directions", "PE", "underlying", "BANKNIFTY", "lots", "1", "skipDays", "MON"));
        roundTrip(B, m("referenceMode", "CPR", "directions", "BOTH", "maxStopPoints", "40", "targetPoints", "80", "onlyMonthEndExpiry", "true", "lots", "4"));
    }

    @Test
    void previousDayGapFillAndEma() {
        roundTrip(B, m("referenceMode", "PREVIOUS_DAY", "directions", "PE", "targetPoints", "60", "maxStopPoints", "150", "lots", "4",
                "underlying", "NIFTY", "skipMonthEndExpiry", "true", "maxTradesPerDay", "1", "skipDays", "THU"));
        roundTrip(B, m("referenceMode", "PREVIOUS_DAY", "directions", "BOTH", "narrowRangeDays", "4", "excludeNarrowRangeDays", "7",
                "targetPoints", "80", "maxStopPoints", "40", "breakevenOnTrail", "true", "trailAfterPoints", "32", "trailMinutes", "0"));
        roundTrip(B, m("referenceMode", "PREVIOUS_DAY", "directions", "CE", "underlying", "BANKNIFTY", "entryUntil", "11:00", "targetPoints", "0", "skipDays", "MON"));
        roundTrip(B, m("referenceMode", "PREVIOUS_DAY", "lots", "2"));
        roundTrip(B, m("referenceMode", "PREVIOUS_DAY", "directions", "CE", "targetPoints", "50", "targetFrom", "LINE", "maxStopPoints", "100", "lots", "4"));
        roundTrip(B, m("referenceMode", "CPR", "targetPoints", "100", "targetFrom", "LINE", "maxStopPoints", "60"));
        assertThat(StrategyDescriber.describe(B, m("referenceMode", "PREVIOUS_DAY", "targetPoints", "50", "targetFrom", "LINE")))
                .contains("Target 50 points from the line.");
        roundTrip(B, m("referenceMode", "GAP_FILL", "directions", "PE", "underlying", "BANKNIFTY", "gapPct", "0.5", "maxGapPct", "2", "gapFillTarget", "0.5",
                "lots", "1", "skipMonthEndExpiry", "true"));
        roundTrip(B, m("referenceMode", "GAP_FILL", "directions", "CE", "gapFillTarget", "1"));
        roundTrip(B, m("referenceMode", "EMA_CROSS", "directions", "BOTH", "fastEma", "9", "slowEma", "21", "swingBars", "3", "entryFrom", "10:00",
                "breakevenOnTrail", "true", "trailAfterPoints", "20", "trailMinutes", "0", "lots", "4"));
    }

    @Test
    void indicatorStrategies() {
        roundTrip("SMA_CROSSOVER", m("fast", "10", "slow", "30"));
        roundTrip("RSI_THRESHOLD", m("period", "14", "oversold", "30", "overbought", "70"));
        roundTrip("MACD_SIGNAL", m("fast", "12", "slow", "26", "signal", "9"));
    }

    @Test
    void readsLikePlainEnglish() {
        assertThat(StrategyDescriber.describe(B, m("referenceMode", "CPR", "maxCprWidthPct", "0.2", "targetPoints", "100", "lots", "4")))
                .isEqualTo("Nifty CPR breakout: pivot, R1 and S1 from yesterday's high, low and close; the day must open between S1 and R1. "
                        + "CE on a 5-minute close above R1, PE below S1, once a day. CPR width at most 0.2%. Stop at the pivot. "
                        + "Target 100 points. Square-off at 15:20. 4 lots.");
    }
}
