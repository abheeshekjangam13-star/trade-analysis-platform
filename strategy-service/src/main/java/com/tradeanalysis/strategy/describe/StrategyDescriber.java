package com.tradeanalysis.strategy.describe;

import java.util.*;

/**
 * Writes a strategy's parameters as a plain-English description that {@link StrategyDescriptionParser} reads back to
 * exactly the same parameters: copy a description into "create from description" and you get the same strategy.
 * Only the settings the strategy has are written (an unset setting stays unset, so the engine default applies).
 * StrategyDescriberTest checks the round trip.
 */
public final class StrategyDescriber {

    private StrategyDescriber() {}

    private static final Map<String, String> DAYS = Map.of("MON", "Monday", "TUE", "Tuesday", "WED", "Wednesday", "THU", "Thursday", "FRI", "Friday");

    public static String describe(String type, Map<String, String> params) {
        Map<String, String> p = new HashMap<>();
        if (params != null) params.forEach((k, v) -> { if (v != null && !v.isBlank()) p.put(k, v.trim()); });
        return switch (type == null ? "" : type) {
            case "SMA_CROSSOVER" -> "SMA " + p.getOrDefault("fast", "10") + "/" + p.getOrDefault("slow", "30") + " crossover.";
            case "RSI_THRESHOLD" -> "RSI " + p.getOrDefault("period", "14") + ", oversold " + p.getOrDefault("oversold", "30")
                    + ", overbought " + p.getOrDefault("overbought", "70") + ".";
            case "MACD_SIGNAL" -> "MACD " + p.getOrDefault("fast", "12") + "/" + p.getOrDefault("slow", "26") + "/" + p.getOrDefault("signal", "9") + " signal crossover.";
            default -> breakout(p);
        };
    }

    private static String breakout(Map<String, String> p) {
        List<String> out = new ArrayList<>();
        String mode = p.getOrDefault("referenceMode", "").toUpperCase(Locale.ROOT);
        String idx = "BANKNIFTY".equalsIgnoreCase(p.get("underlying")) ? "Bank Nifty" : p.containsKey("underlying") ? "Nifty 50" : "Nifty";
        String dirs = p.getOrDefault("directions", "").toUpperCase(Locale.ROOT);

        switch (mode) {
            case "CPR" -> {
                out.add(idx + " CPR breakout: pivot, R1 and S1 from yesterday's high, low and close; the day must open between S1 and R1.");
                out.add(switch (dirs) {
                    case "CE" -> "CE only, on a 5-minute close above R1, once a day.";
                    case "PE" -> "PE only, on a 5-minute close below S1, once a day.";
                    case "BOTH" -> "Both sides: CE on a 5-minute close above R1, PE below S1, once a day.";
                    default -> "CE on a 5-minute close above R1, PE below S1, once a day.";
                });
                String min = p.get("minCprWidthPct"), max = p.get("maxCprWidthPct");
                if (min != null && max != null) out.add("CPR width between " + min + "% and " + max + "%.");
                else if (min != null) out.add("CPR wider than " + min + "%.");
                else if (max != null) out.add("CPR width at most " + max + "%.");
                out.add("Stop at the pivot.");
            }
            case "PREVIOUS_DAY" -> {
                out.add(idx + " previous-day range breakout: the day must open inside yesterday's range.");
                out.add(switch (dirs) {
                    case "CE" -> "CE only, on a 5-minute close above yesterday's high. Stop at yesterday's low.";
                    case "PE" -> "PE only, on a 5-minute close below yesterday's low. Stop at yesterday's high.";
                    case "BOTH" -> "Both sides: CE on a close above yesterday's high, PE on a close below yesterday's low. Stop at yesterday's low (CE) / high (PE).";
                    default -> "Trades the engine's default side. Stop at yesterday's high or low.";
                });
            }
            case "GAP_FILL" -> {
                out.add(idx + " gap fill: after an opening gap, trades back towards yesterday's close.");
                if (dirs.equals("CE")) out.add("CE only.");
                else if (dirs.equals("PE")) out.add("PE only.");
                else if (dirs.equals("BOTH")) out.add("Both sides.");
                String gap = p.get("gapPct");
                if (gap != null) out.add(dirs.equals("CE") ? "Gap-down days above " + gap + "%." : dirs.equals("PE") ? "Gap-up days above " + gap + "%."
                        : "Gap-up or gap-down days above " + gap + "%.");
                if (p.containsKey("maxGapPct")) out.add("Skip gaps above " + p.get("maxGapPct") + "%.");
                String t = p.get("gapFillTarget");
                if (t != null) out.add("1".equals(t) || "1.0".equals(t) ? "Target the full gap fill." : "0.5".equals(t) ? "Target half the gap."
                        : "Target " + num(Double.parseDouble(t) * 100) + "% of the gap.");
            }
            case "EMA_CROSS" -> {
                out.add(idx + (p.containsKey("fastEma") || p.containsKey("slowEma")
                        ? " " + p.getOrDefault("fastEma", "9") + "/" + p.getOrDefault("slowEma", "21") + " EMA crossover on 5-minute closes."
                        : " EMA crossover on 5-minute closes."));
                if (dirs.equals("CE")) out.add("CE only.");
                else if (dirs.equals("PE")) out.add("PE only.");
                else if (dirs.equals("BOTH")) out.add("Both sides.");
                if (p.containsKey("swingBars")) out.add("Stop at the lowest low of the last " + p.get("swingBars") + " candles.");
            }
            default -> {
                out.add(idx + " opening range breakout.");
                if (p.containsKey("referenceTime")) out.add("Reference candle at " + p.get("referenceTime") + ".");
                if (p.containsKey("referenceMinutes")) out.add("Reference range " + p.get("referenceMinutes") + " minutes.");
                switch (dirs) {
                    case "CE" -> out.add("CE only.");
                    case "PE" -> out.add("PE only.");
                    case "BOTH" -> out.add("Both sides.");
                    case "WITH_GAP" -> out.add("Trade in the direction of the gap.");
                    default -> { }
                }
                switch (p.getOrDefault("initialStop", "").toUpperCase(Locale.ROOT)) {
                    case "OPPOSITE" -> out.add("Stop at the opposite side of the range.");
                    case "MIDPOINT" -> out.add("Stop at the midpoint of the range.");
                    case "BREAKOUT_CANDLE" -> out.add("Stop at the breakout candle's low.");
                    default -> { }
                }
                gap(p, out);
                if (p.containsKey("trendSma")) out.add(p.get("trendSma") + "-session trend.");
                if (p.containsKey("breakoutStrength")) out.add("Breakout strength " + p.get("breakoutStrength") + ".");
                if (p.containsKey("failedBreakoutPoints")) out.add("Failed breakout exit " + p.get("failedBreakoutPoints") + " points.");
                if (p.containsKey("onlyOn")) out.add("Only on " + p.get("onlyOn") + ".");
            }
        }
        if (!mode.equals("GAP_FILL") && !mode.equals("EMA_CROSS") && p.containsKey("narrowRangeDays"))
            out.add("After an NR" + p.get("narrowRangeDays") + " day" + (p.containsKey("excludeNarrowRangeDays") ? ", but not also an NR" + p.get("excludeNarrowRangeDays") + " day." : "."));
        if (p.containsKey("maxTradesPerDay")) out.add(p.get("maxTradesPerDay") + " trade" + ("1".equals(p.get("maxTradesPerDay")) ? "" : "s") + " per day.");

        if (p.containsKey("entryFrom")) out.add("No entries before " + p.get("entryFrom") + ".");
        if (p.containsKey("entryUntil")) out.add("No new entries after " + p.get("entryUntil") + ".");
        if (p.containsKey("skipDays")) {
            List<String> d = new ArrayList<>();
            for (String s : p.get("skipDays").split(",")) { String n = DAYS.get(s.trim().toUpperCase(Locale.ROOT)); if (n != null) d.add(n); }
            if (!d.isEmpty()) out.add("Skip " + String.join(" and ", d) + ".");
        }
        if ("true".equalsIgnoreCase(p.get("skipMonthEndExpiry"))) out.add("Skip the month-end expiry day.");
        if ("true".equalsIgnoreCase(p.get("onlyMonthEndExpiry"))) out.add("Only on the month-end expiry day.");

        if (p.containsKey("maxStopPoints")) out.add("Stop never more than " + p.get("maxStopPoints") + " points from entry.");
        if (p.containsKey("trailAfterPoints")) out.add("Trail after " + p.get("trailAfterPoints") + " points.");
        if (p.containsKey("trailAfterR")) out.add("Trail after " + p.get("trailAfterR") + "R.");
        if ("true".equalsIgnoreCase(p.get("breakevenOnTrail"))) out.add("Breakeven on trail.");
        if (p.containsKey("breakevenLockPoints")) out.add("Locking " + p.get("breakevenLockPoints") + " points.");
        if (p.containsKey("trailMinutes"))
            out.add("0".equals(p.get("trailMinutes")) ? "No candle trail." : "Trail on " + p.get("trailMinutes") + "-minute lows and highs.");

        if (p.containsKey("targetR")) out.add("Target " + p.get("targetR") + "R.");
        if (p.containsKey("targetPoints")) out.add("0".equals(p.get("targetPoints")) ? "No target."
                : "Target " + p.get("targetPoints") + " points" + ("LINE".equalsIgnoreCase(p.get("targetFrom")) ? " from the line." : "."));
        out.add("Square-off at 15:20.");

        if (p.containsKey("lots")) out.add(StrategyDescriptionParser.lots(p.get("lots")) + ".");
        if (p.containsKey("costPerTrade")) out.add("Charges ₹" + p.get("costPerTrade") + " per trade.");
        if (p.containsKey("slippagePoints")) out.add("Slippage " + p.get("slippagePoints") + " points.");
        return String.join(" ", out);
    }

    /** The opening-range gap filter, in the words the parser reads (% or points, with the optional maximum). */
    private static void gap(Map<String, String> p, List<String> out) {
        String f = p.getOrDefault("gapFilter", "").toUpperCase(Locale.ROOT);
        boolean pts = p.containsKey("gapPoints");
        String size = pts ? p.get("gapPoints") + " points" : p.containsKey("gapPct") ? p.get("gapPct") + "%" : null;
        switch (f) {
            case "GAP_UP" -> out.add(size == null ? "Only on gap-up days." : "Only on gap-up days above " + size + ".");
            case "GAP_DOWN" -> out.add(size == null ? "Only on gap-down days." : "Only on gap-down days above " + size + ".");
            case "FLAT" -> out.add(size == null ? "Only on flat opens." : "Only on flat opens within " + size + " of the previous close.");
            case "NOT_GAP_DOWN" -> out.add(size == null ? "Except on gap-down days." : "Except on gap-down days of " + size + " or more.");
            case "ANY_GAP" -> out.add(size == null ? "Only on gap days." : "Opens at least " + size + " away from the previous close.");
            default -> { if (size != null) out.add("Gap size " + size + "."); }
        }
        if (p.containsKey("maxGapPoints")) out.add("Skip gaps above " + p.get("maxGapPoints") + " points.");
        else if (p.containsKey("maxGapPct")) out.add("Skip gaps above " + p.get("maxGapPct") + "%.");
    }

    private static String num(double v) {
        String s = String.valueOf(Math.round(v * 100) / 100.0);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
