package com.tradeanalysis.strategy.describe;

import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.strategy.engine.NiftyOpeningBreakoutEngine;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads strategy parameters from a plain-English description, e.g.
 * "CE only on gap-up days above 0.2%, 3 lots, 1 trade per day, trail on 5-min lows, target 80 points, no entry after 14:00".
 *
 * Deterministic rules (no AI): every rule records the words it matched, so the user can see why each parameter was
 * set, and sentences no rule understood are returned for the user to check before confirming. Values outside a sane
 * range are not set and come back as warnings. The result is only a proposal; nothing is saved here.
 */
@Component
public class StrategyDescriptionParser {

    public record Finding(String key, String value, String text, String note) {}

    public record Result(String type, boolean typeDetected, Map<String, String> parameters, List<Finding> findings,
                         List<String> warnings, List<String> notUnderstood, String suggestedName) {}

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String NUM = "(\\d+(?:\\.\\d+)?)";
    private static final String TIME = "(\\d{1,2}(?:[:.]\\d{2})?\\s*(?:am|pm)?)";
    /** 2026-11-08, 08-11-2026, 8 Nov 2026 or 8 November 2026. */
    private static final String DATE = "(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{4}|\\d{1,2}\\s+[a-z]{3,9}\\s+\\d{4})";
    private static final Map<String, Integer> WORDS = Map.of("one", 1, "two", 2, "three", 3, "four", 4, "five", 5,
            "single", 1, "a", 1, "once", 1, "twice", 2);
    private static final String COUNT = "(\\d+|one|two|three|four|five|single|a)";

    /** Words that are always fine to leave out of the "not understood" list. */
    private static final Pattern FILLER = Pattern.compile("^[\\s\\p{Punct}–—₹]*(and|with|then|also|only|the|a|an|on|for|of|in|at|to|use|strategy|nifty(\\s*50)?)?[\\s\\p{Punct}–—]*$", F);

    public Result parse(String description) {
        String text = normalize(description == null ? "" : description);
        Scan scan = new Scan(text);

        String type = detectType(scan);
        boolean detected = type != null;
        if (type == null) type = "NIFTY_OPENING_BREAKOUT";

        // The index: "Bank Nifty" trades BANKNIFTY (monthly expiry, 100-point strikes, lot 30); NIFTY is the default.
        if (scan.find("\\b(bank\\s*nifty|banknifty|nifty\\s*bank)\\b")) {
            scan.params.put("underlying", "BANKNIFTY");
            scan.builtin("\\b(bank\\s*nifty|banknifty|nifty\\s*bank)\\b",
                    "Index: Bank Nifty (monthly expiry, 100-point strikes, lot 30)");
        }

        switch (type) {
            case "SMA_CROSSOVER" -> sma(scan);
            case "RSI_THRESHOLD" -> rsi(scan);
            case "MACD_SIGNAL" -> macd(scan);
            default -> breakout(scan);
        }

        if (type.equals("NIFTY_OPENING_BREAKOUT")) {
            try {
                Params p = new Params(new HashMap<>(scan.params));
                NiftyOpeningBreakoutEngine.validateParams(p);
            } catch (IllegalArgumentException e) {
                scan.warnings.add("These parameters would be rejected: " + e.getMessage());
            }
        }
        if (text.isBlank()) scan.warnings.add("Describe the strategy first");
        else if (scan.params.isEmpty()) scan.warnings.add("No parameters found; fill them in by hand or describe the rules in more detail");

        return new Result(type, detected, scan.params, scan.findings, scan.warnings, scan.leftovers(), suggestName(type, scan.params));
    }

    /**
     * Text copied from web pages often loses the space between a heading and the next sentence ("Trail the SLAfter
     * every", "gapAt 9:20"): put it back before a capitalised word glued to the previous one. Arrows become spaces.
     */
    static String normalize(String text) {
        return text.replaceAll("(?<=[\\p{L}?)%])(?=\\p{Lu}\\p{Ll}{2,})", " ")
                .replaceAll("\\s*(→|->|=>|⇒)\\s*", " → ");
    }

    // ── Type ─────────────────────────────────────────────────────────────────

    private String detectType(Scan s) {
        // An EMA crossover that buys CE/PE is the intraday option engine (EMA_CROSS), not the equity SMA strategy.
        if (s.find("\\bema\\b") && s.find("\\b(ce|pe|calls?|puts?)\\b")) return "NIFTY_OPENING_BREAKOUT";
        if (s.find("\\b(sma|simple moving average|moving[- ]average)\\b.*\\bcross") || s.find("\\b\\d+\\s*/\\s*\\d+\\s*(sma|ma)\\b"))
            return "SMA_CROSSOVER";
        if (s.find("\\brsi\\b")) return "RSI_THRESHOLD";
        if (s.find("\\bmacd\\b")) return "MACD_SIGNAL";
        if (s.find("\\b(ce|pe|calls?|puts?|breakout|breakdown|orb|opening range|gap[- ]?(up|down)|nifty)\\b")) return "NIFTY_OPENING_BREAKOUT";
        return null;
    }

    // ── NIFTY opening breakout ───────────────────────────────────────────────

    private void breakout(Scan s) {
        // Step-by-step write-ups: headings and flow words carry no rule of their own.
        s.ignore("\\b(here'?s|here\\s+is|this\\s+is)\\b[^.\\n]{0,60}?\\b(strategy|setup|rules?|plan)\\b([^.\\n]{0,30}?\\bstep[- ]by[- ]step\\b)?\\s*:?");
        s.ignore("\\bstep\\s*\\d{1,2}\\s*[—–:.)-]?");
        s.ignore("\\b(all\\s+(\\d+|three|two|four)\\s+)?conditions?\\s+(are\\s+)?(met|passed|satisfied)\\b");
        s.ignore("\\bif\\s+no\\s*→?\\s*$|\\bif\\s+(yes|no)\\s*(→|:|,)");
        s.ignore("\\b(market\\s+opens?|at\\s+market\\s+open)\\s*(\\(?\\s*(at\\s+)?9[:.]15\\s*(am|ist)?\\s*\\)?)?");
        s.ignore("\\benter\\s+the\\s+trade\\b");

        // ── Pivot (CPR) breakout: R1 / S1 from yesterday, stop at the pivot (CprEngine) ──
        boolean cpr = s.peek("\\bcpr\\b|\\bcentral\\s+pivot\\b|\\bpivot\\s+(point\\s+)?(breakout|levels?)\\b|\\b(above|below)\\s+(the\\s+)?(r1|s1)\\b");
        if (cpr) {
            s.params.put("referenceMode", "CPR");
            s.findings.add(new Finding("referenceMode", "CPR", "CPR / pivot", "Pivot (CPR) breakout: CE above yesterday's R1, PE below S1"));
            s.match("\\b(cpr|cpr\\s+band|cpr\\s+width)\\s+(is\\s+)?(narrower\\s+than|below|under|less\\s+than|at\\s+most|up\\s+to|<=?|≤)\\s*" + NUM + "\\s*%"
                    + "|\\bnarrow[- ]cpr\\s*(days?)?\\s*\\(?\\s*(width\\s+)?(below|under|at\\s+most|up\\s+to|<=?|≤)\\s*" + NUM + "\\s*%\\s*\\)?",
                    m -> s.setNum("maxCprWidthPct", m.group(4) != null ? m.group(4) : m.group(8), 0.01, 5, m));
            s.match("\\bnarrow[- ]cpr\\b", m -> { if (!s.params.containsKey("maxCprWidthPct")) s.note(m, "Narrow CPR: give the width, e.g. \"CPR narrower than 0.15%\""); });
            s.builtin("\\b(pivot|cpr)\\s+levels?\\s+(from|of)\\s+(the\\s+)?(previous|prior|yesterday'?s?)\\s+(session|day)\\b", "Pivot levels from yesterday's high, low and close");
            s.builtin("\\bopens?\\s+between\\s+(the\\s+)?s1\\s+and\\s+(the\\s+)?r1\\b", "The day must open between S1 and R1");
            s.builtin("\\b(a\\s+)?5[- ]?min(ute)?\\s+candle\\s+closes\\s+(above|below)\\s+(the\\s+)?(r1|s1)\\b|\\bcloses?\\s+(above|below)\\s+(the\\s+)?(r1|s1)\\b",
                    "Entry on a 5-min close above R1 (CE) / below S1 (PE)");
            s.builtin("\\b(sl|stop(?:[- ]loss)?)\\s+(at|on|=)\\s+(the\\s+)?pivot\\b", "Stop at the pivot");
        }

        // ── EMA crossover on 5-min candles (EmaCrossEngine) ──
        boolean emaCross = s.peek("\\bema\\b");
        if (emaCross) {
            s.params.put("referenceMode", "EMA_CROSS");
            s.findings.add(new Finding("referenceMode", "EMA_CROSS", "EMA crossover", "5-min EMA crossover: CE when the fast EMA crosses above the slow one, PE below"));
            s.match("\\b(\\d{1,3})\\s*ema\\s+cross(es|ing)?\\s+(above|over|below|under)\\s+(the\\s+)?(\\d{1,3})\\s*ema\\b",
                    m -> { s.setInt("fastEma", m.group(1), 2, 200, m); s.setInt("slowEma", m.group(5), 2, 400, m); });
            s.match("\\bema\\s*\\(?\\s*(\\d{1,3})\\s*/\\s*(\\d{1,3})\\s*\\)?(\\s+cross(over|es)?)?\\b|\\b(\\d{1,3})\\s*/\\s*(\\d{1,3})\\s*ema(\\s+cross(over|es)?)?\\b",
                    m -> { s.setInt("fastEma", first(m, 1, 5), 2, 200, m); s.setInt("slowEma", first(m, 2, 6), 2, 400, m); });
            s.match("\\b(sl|stop(?:[- ]loss)?)\\s+(at|on|below|above)\\s+(the\\s+)?(low|high|lowest\\s+low|highest\\s+high)"
                    + "(\\s*\\(?\\s*(ce|pe)\\s*\\)?\\s*(/|or|and)\\s*(the\\s+)?(low|high|lowest\\s+low|highest\\s+high)\\s*\\(?\\s*(ce|pe)\\s*\\)?)?"
                    + "\\s+of\\s+the\\s+last\\s+(?<n>\\d{1,2})\\s+(5[- ]?min(ute)?\\s+)?candles\\b",
                    m -> s.setInt("swingBars", m.group("n"), 1, 20, m));
            s.builtin("\\b(ce|pe)\\s+when\\s+it\\s+crosses\\s+(above|below)\\b", "The other side on the opposite cross");
            s.builtin("\\bema\\s+cross(over|es)?\\b|\\bcross(es)?\\s+(above|below)\\s+the\\s+slow\\s+ema\\b", "EMA crossover");
        }

        // ── Reference: yesterday's high-low range instead of today's opening candle (PreviousRangeEngine) ──
        String YDAY = "(yesterday'?s?|previous[- ]day'?s?|prev(ious)?\\s+day'?s?|prior[- ]day'?s?|pdh|pdl)";
        boolean prevDay = !cpr && !emaCross && s.peek("\\b" + YDAY + "\\s+(high\\s*[-/–]?\\s*low\\s+)?(range|high|low)\\b|\\bpd[hl]\\b");
        if (prevDay) {
            s.params.put("referenceMode", "PREVIOUS_DAY");
            s.findings.add(new Finding("referenceMode", "PREVIOUS_DAY", "yesterday's high and low",
                    "Reference: yesterday's high and low (not today's 09:15 candle)"));
            s.builtin("\\b(on\\s+)?(days?\\s+(that|which)\\s+)?opens?\\s+(inside|within|in)\\s+(the\\s+)?" + YDAY + "\\s+(high\\s*[-/–]?\\s*low\\s+)?range\\b",
                    "The day must open inside yesterday's range");
            s.builtin("\\b(a\\s+)?5[- ]?min(ute)?\\s+candle\\s+closes\\s+(below|above)\\s+" + YDAY + "\\s+(low|high)\\b|\\bclose[sd]?\\s+(below|above)\\s+" + YDAY + "\\s+(low|high)\\b",
                    "Entry on a 5-min close beyond yesterday's low (PE) / high (CE)");
            s.builtin("\\b(sl|stop(?:[- ]loss)?)\\s+(at|on|=)\\s+" + YDAY + "\\s+(high|low)\\b",
                    "Stop at yesterday's high (PE) / low (CE)");
            // Narrow-range days: NR7 = yesterday's range the smallest of the last 7 sessions. The exclusion comes first.
            s.match("\\b(but\\s+)?not\\s+(also\\s+)?(an?\\s+)?nr\\s*-?(\\d{1,2})(\\s+days?)?\\b",
                    m -> s.setInt("excludeNarrowRangeDays", m.group(4), 3, 20, m));
            s.match("\\b(after\\s+)?(an?\\s+)?nr\\s*-?(\\d{1,2})(\\s+days?)?\\b",
                    m -> s.setInt("narrowRangeDays", m.group(3), 2, 20, m));
            s.match("\\b(when\\s+)?" + YDAY + "\\s+(high\\s*[-/–]?\\s*low\\s+)?range\\s+(was|is)\\s+the\\s+(smallest|narrowest|lowest)\\s+(of|in)\\s+the\\s+last\\s+(?<n>\\d{1,2})\\s+(sessions|days|trading\\s+days)\\b",
                    m -> s.setInt("narrowRangeDays", m.group("n"), 2, 20, m));
            s.builtin("\\b" + YDAY + "\\s+(high\\s*[-/–]?\\s*low\\s+)?range\\b", "Yesterday's high-low range");
        }
        // ── Gap fill: after an opening gap, trade back towards yesterday's close once price re-enters the gap (GapFillEngine) ──
        boolean gapFill = s.peek("\\bgap[- ]?fill(ing|s)?\\b|\\bfill(s|ing)?\\s+(the|its)\\s+gap\\b|\\b(back\\s+)?(in|into|inside)\\s+the\\s+gap\\b|\\bre-?enters?\\s+the\\s+gap\\b");
        if (gapFill) {
            s.params.put("referenceMode", "GAP_FILL");
            s.findings.add(new Finding("referenceMode", "GAP_FILL", "gap fill", "Gap fill: trade back towards yesterday's close once price re-enters the gap"));
            // The target as a share of the gap, read before the point targets ("target 50% of the gap" is not 50 points).
            s.match("\\btarget\\s+(the\\s+)?(full\\s+)?(gap\\s+fill|fill\\s+of\\s+the\\s+gap|yesterday'?s\\s+close|previous\\s+close|prev(ious)?\\s+day'?s?\\s+close)\\b",
                    m -> s.set("gapFillTarget", "1", m, "Target: the full gap fill (yesterday's close)"));
            s.match("\\btarget\\s+(a\\s+)?half(\\s+of)?\\s+(the\\s+)?gap(\\s+fill(ed)?)?\\b|\\bhalf[- ]gap\\s+(fill|target)\\b|\\btarget\\s+(the\\s+)?(middle|midpoint)\\s+of\\s+the\\s+gap\\b",
                    m -> s.set("gapFillTarget", "0.5", m, "Target: half the gap filled"));
            s.match("\\btarget\\s+" + NUM + "\\s*%\\s+(of\\s+)?(the\\s+)?gap(\\s+fill(ed)?)?\\b",
                    m -> s.setNum("gapFillTarget", trim(String.valueOf(Double.parseDouble(m.group(1)) / 100)), 0.01, 1, m));
            s.builtin("\\b(once|when|after)\\s+(the\\s+)?(price|market|index|it|nifty|bank\\s*nifty)\\s+(falls|drops|comes|rises|climbs|goes|moves|trades)?\\s*(back\\s+)?(below|above|under|over|into|in|inside)\\s+(today'?s\\s+open|the\\s+open(ing)?(\\s+price)?|the\\s+gap)\\b",
                    "Entry on a 5-min close back past today's open, into the gap");
            s.builtin("\\b(a\\s+)?5[- ]?min(ute)?\\s+candle\\s+closes\\s+(back\\s+)?(below|above|past)\\s+(today'?s\\s+)?open\\b",
                    "Entry on a 5-min close back past today's open, into the gap");
            s.builtin("\\b(sl|stop(?:[- ]loss)?)\\s+(at|on|=)\\s+(the\\s+)?(09[:.]15|first|reference|opening)\\s+candle'?s?\\s+(high|low)\\b(\\s*\\(?\\s*(pe|ce)\\s*\\)?\\s*(/|or)\\s*(high|low)\\s*\\(?\\s*(pe|ce)\\s*\\)?)?",
                    "Stop at the 09:15 candle's high (PE) / low (CE)");
            s.builtin("\\bgap[- ]?fill(ing|s)?\\b|\\b(back\\s+)?(in|into|inside)\\s+the\\s+gap\\b|\\bre-?enters?\\s+the\\s+gap\\b", "Gap fill");
        }
        s.builtin("\\bfresh\\s+cross(es|ing)?\\b", "Fresh crosses only");
        s.builtin("\\bfrom\\s+the\\s+09?[:.]20\\s+candle\\b", "Entries checked from the 09:20 candle");

        // ── Reference range length: "first 15-min candle", "09:15-09:30", "15-minute opening range" ──
        s.match("\\b(first|opening|reference|ref)\\s+(\\d{1,2})[- ]?min(ute)?s?\\s+(candle|range|bar)(\\s*\\(?\\s*09[:.]15\\s*[-–]\\s*\\d{1,2}[:.]\\d{2}(\\s*ist)?\\s*\\)?)?"
                + "|\\b(\\d{1,2})[- ]?min(ute)?s?\\s+(opening\\s+range|reference(\\s+(candle|range))?|orb)\\b",
                m -> s.setReferenceMinutes(m.group(2) != null ? m.group(2) : m.group(7), m));
        s.match("\\b(reference|ref|opening\\s+range|range)(\\s+(candle|range))?\\s*(=|:|is|from)?\\s*09[:.]15\\s*[-–]\\s*(09|10)[:.](\\d{2})(\\s*ist)?",
                m -> s.setReferenceMinutes(String.valueOf((Integer.parseInt(m.group(5)) - 9) * 60 + Integer.parseInt(m.group(6)) - 15), m));

        // The breakout itself: "a close beyond the 15-min high/low" is the reference range, not a trail block.
        s.builtin("\\b(beyond|above|below|outside)\\s+the\\s+(\\d{1,2})[- ]?min(ute)?\\s+(range\\s+)?(high|low)(\\s*/\\s*(high|low))?\\b",
                "Entry on a 5-min close beyond the reference range");

        // ── Trade in the direction of the gap: CE on gap-up days, PE on gap-down days ──
        s.match("\\b(in\\s+the\\s+direction\\s+of\\s+the\\s+gap|with\\s+the\\s+gap|gap\\s+direction(\\s+only)?|trade\\s+the\\s+gap(\\s+direction)?)\\b"
                + "|\\bgap[- ]?up\\s*(→|->|:|=)?\\s*ce\\b[^.\\n]{0,120}?\\bgap[- ]?down\\s*(→|->|:|=)?\\s*pe\\b",
                m -> s.set("directions", "WITH_GAP", m, "CE only on gap-up days, PE only on gap-down days"));

        // ── Initial stop at the 5-min breakout candle's own low (CE) / high (PE) ──
        s.match("\\b(initial\\s+)?(sl|stop(?:[- ]loss)?)\\s*(=|:|at|on|below|above)?\\s*(the\\s+)?break(out|down)\\s+candle'?s?\\s+(low|high)(\\s*\\(?\\s*(ce|pe)\\s*\\)?\\s*(/|or|and)\\s*(low|high)\\s*\\(?\\s*(ce|pe)\\s*\\)?)?"
                + "|\\b(sl|stop(?:[- ]loss)?)\\s+(at|on|below|above)\\s+(the\\s+)?(low|high)\\s+of\\s+(the\\s+)?break(out|down)\\s+candle\\b",
                m -> s.set("initialStop", "BREAKOUT_CANDLE", m, "Initial SL at the breakout candle's low (CE) / high (PE)"));

        // ── Target and trail as multiples of the initial risk (R) ──
        s.match("\\btarget(\\s+(of|at|=|is))?\\s*\\+?" + NUM + "\\s*r\\b(?!s)|\\b" + NUM + "\\s*r\\s+target\\b"
                + "|\\b(risk[- :]?reward|rr|r:r)\\s*(of|=|:)?\\s*1\\s*:\\s*(\\d(?:\\.\\d)?)(?!\\d)|\\b1\\s*[:-]\\s*(\\d(?:\\.\\d)?)(?!\\d)\\s*(target|rr|r:r|risk[- :]?reward)\\b"
                + "|\\btarget\\s+(at\\s+)?" + NUM + "\\s*(x|times)\\s+(the\\s+)?(initial\\s+)?risk\\b",
                m -> s.setNum("targetR", first(m, 3, 4, 7, 8, 11), 0.1, 20, m));
        s.match("\\b(trail(ing)?|move\\s+(the\\s+)?(sl|stop)(\\s+to\\s+(entry|break[- ]?even|cost))?)\\s+(only\\s+)?(after|once|at)\\s+\\+?" + NUM + "\\s*r\\b(\\s+in\\s+(profit|favou?r))?"
                + "|\\b(at|after|once)\\s+\\+?" + NUM + "\\s*r\\b(\\s+in\\s+(profit|favou?r))?\\s*,?\\s*(move\\s+)?(the\\s+)?(sl|stop)\\s+(moves\\s+)?to\\s+(entry|break[- ]?even|cost)",
                m -> {
                    s.setNum("trailAfterR", first(m, 9, 13), 0.1, 20, m);
                    String t = s.text(m).toLowerCase(Locale.ROOT);
                    if (t.matches(".*\\b(entry|break[- ]?even|cost)\\b.*")) s.params.put("breakevenOnTrail", "true");
                });

        // ── Breakout strength: the breakout candle must close near its high (CE) / low (PE) ──
        s.match("\\bclos(es|ing|e)\\s+in\\s+(its|the)\\s+(strong|upper|top)\\s+half\\b|\\bstrong[- ]half\\s+close\\b",
                m -> s.setNum("breakoutStrength", "0.5", 0, 1, m));
        s.match("\\bclos(es|ing|e)\\s+in\\s+(the\\s+)?(top|upper|strongest)\\s+" + NUM + "\\s*%\\s+of\\s+(its|the)\\s+(range|candle)\\b",
                m -> s.setNum("breakoutStrength", trim(String.valueOf(Math.round((100 - Double.parseDouble(m.group(4))) / 100.0 * 100) / 100.0)), 0, 1, m));
        s.match("\\bclos(es|ing|e)\\s+in\\s+(the\\s+)?(top|upper)\\s+third\\b", m -> s.setNum("breakoutStrength", "0.67", 0, 1, m));
        s.match("\\bbreakout\\s+strength\\s*(of|=|:)?\\s*" + NUM, m -> s.setNum("breakoutStrength", m.group(2), 0, 1, m));

        // ── Trend filter: CE only above the N-session average of closes, PE only below ──
        s.match("\\b(in\\s+the\\s+direction\\s+of\\s+the\\s+)?(\\d{1,3})[- ]?(day|session|period|bar)s?\\s+(trend|average|avg|sma|moving\\s+average|ma)\\b"
                + "|\\btrend\\s+(filter|sma|average)\\s*(of|=|:)?\\s*(\\d{1,3})\\b",
                m -> s.setInt("trendSma", m.group(2) != null ? m.group(2) : m.group(7), 2, 200, m));
        s.builtin("\\b(only\\s+if\\s+)?(the\\s+)?previous\\s+close\\s+(is\\s+)?(above|below)\\s+(its|the)\\s+(average|sma|mean)\\b", "Trend check on the previous session's close");

        // Initial stop at the reference candle's own low (CE) / high (PE) = the OPPOSITE side.
        s.match("\\b(note|mark)\\s+its\\s+(low|high)\\b[^.;\\n]{0,40}\\binitial\\s+(sl|stop(?:[- ]loss)?)\\b"
                + "|\\b(sl|stop(?:[- ]loss)?)\\s+(at|on|below|above)\\s+(the\\s+)?(low|high)\\s+of\\s+(the\\s+)?(first|reference|opening|9[:.]15)\\b[^.;\\n]{0,40}?candle(\\s*\\(?\\s*9[:.]15\\s+candle\\s+(low|high)\\s*\\)?)?"
                + "|\\b(sl|stop(?:[- ]loss)?)\\s+(at|on|below|above)\\s+(the\\s+)?(9[:.]15|first|reference)\\s+candle'?s?\\s+(low|high)\\b",
                m -> s.set("initialStop", "OPPOSITE", m, "Initial SL at the reference candle's low (CE) / high (PE)"));

        // Reference candle at another time (special sessions such as Muhurat trading), and a single trading date.
        s.match("\\b(reference|ref|first|opening)\\s*(5[- ]?min(ute)?\\s*)?candle\\s*(at|from|starts?\\s+at|=|:)\\s*" + TIME
                + "|\\breference\\s+time\\s*(=|:|at|of)?\\s*" + TIME,
                m -> s.setTime("referenceTime", m.group(5) != null ? m.group(5) : m.group(7), m));
        s.match("\\b(trade\\s+)?only\\s+on\\s+" + DATE + "|\\bon\\s+" + DATE + "\\s+only\\b",
                m -> s.setDate("onlyOn", m.group(2) != null ? m.group(2) : m.group(3), m));

        // The entry is the engine's breakout: a 5-min candle crossing the reference candle.
        s.builtin("\\b(watch|observe|check|see)?\\s*(if\\s+)?(the\\s+)?(9[:.]2[05]|next|second)\\s+candle\\s+(closes\\s+|breaks\\s+|crosses\\s+|barely\\s+crosses\\s+)(above|below|over)\\s+(the\\s+)?(9[:.]1?[05]|first|reference)\\s+candle('?s\\s+(high|low))?",
                "Entry when a 5-min candle breaks the reference candle");
        s.builtin("\\bif\\s+it\\s+(doesn'?t|does\\s+not)\\s+(cross|break)[^.;\\n]{0,20}?(→\\s*)?no\\s+trade\\b", "No breakout, no trade");
        s.builtin("\\brepeat\\b[^.;\\n]{0,15}\\buntil\\b[^.;\\n]{0,40}\\b(target|stopped\\s+out|sl)\\b[^.;\\n]*", "Exit at the target, the stop or the 15:20 square-off");

        // Rules this strategy type cannot apply: say so plainly instead of "not understood".
        s.match("\\b(barely|marginal(ly)?|just|slightly)\\s+cross(es|ing)?\\b|\\bmarginal\\s+(cross|break(out)?)\\b|\\bstrong\\s+breakouts?\\s+(doesn'?t|does\\s+not|don'?t)\\s+qualify\\b|\\bit\\s+should\\s+be\\s+a\\s+marginal\\s+cross\\b",
                m -> s.unsupported(m, "Breakout size is not checked: the engine enters on any breakout of the reference candle, strong or small"));
        s.match("\\b(near|at|close\\s+to)\\s+(a\\s+)?(key\\s+)?(resistance|support)(\\s*(/|or|and)\\s*(resistance|support))?(\\s+level)?\\b|\\b(check\\s+)?(resistance|support)\\s*(/|or|and)\\s*(resistance|support)\\b|\\bavoid\\s+the\\s+trade\\b",
                m -> s.unsupported(m, "Support/resistance is not part of this strategy type; check it yourself before trading"));

        // Rules the engine always applies: recognise them so they don't show up as "not understood".
        s.builtin("(first|opening|reference|ref)\\s*(5[- ]?min(ute)?)?\\s*candle(\\s*\\(?\\s*09[:.]15\\s*[-–]\\s*09[:.]20\\s*(ist)?\\s*\\)?)?", "Reference candle 09:15–09:20 IST");
        s.builtin("\\batm\\s*[-−]\\s*50\\b|\\batm\\s*\\+\\s*50\\b|\\bnearest\\s+50\\b", "Strikes: CE at ATM−50, PE at ATM+50");
        s.builtin("(eod|end[- ]of[- ]day)\\s*(square[- ]?off|exit)?(\\s*(at|by)?\\s*15[:.]20(\\s*ist)?)?|square[- ]?off\\s*(at|by)?\\s*15[:.]20", "Square-off at 15:20 IST");
        s.builtin("\\bfresh\\s+break(out|down)s?(\\s+only)?\\b", "Fresh breakouts only");
        s.builtin("\\bmax(imum)?\\s+(1|one)\\s+open\\s+position\\b", "One open position at a time");

        // Stop-loss style
        if (s.match("\\b(sl|stop(?:[- ]loss)?)\\b[^.;\\n]{0,30}\\b(opposite|other)\\s+(side|end)\\b", m -> s.set("initialStop", "OPPOSITE", m, "Initial SL on the opposite side of the reference candle")) == 0)
            s.match("\\b(sl|stop(?:[- ]loss)?)\\b[^.;\\n]{0,30}\\b(mid[- ]?point|middle|mid)\\b|\\bmid[- ]?point\\s+(sl|stop)", m -> s.set("initialStop", "MIDPOINT", m, "Initial SL at the reference-candle midpoint"));

        // Directions
        if (!s.peek("\\b(pe|puts?|bearish|short)\\b"))
            s.match("\\b(enter|go|take\\s+a|buy)\\s+long\\b|\\benter\\s+long\\b|\\b(go|going)\\s+long\\b|\\(\\s*buy\\s*\\)",
                    m -> s.set("directions", "CE", m, "Long (buy) = CE (calls)"));
        boolean ceOnly = s.params.containsKey("directions") || s.match("\\b(ce|calls?|bullish|long)\\s+(only|side only|trades only)\\b|\\bonly\\s+(ce|calls?|bullish|long)\\b|\\b(no|skip|without)\\s+(pe|puts?|bearish)\\b", m -> s.set("directions", "CE", m, null)) > 0;
        boolean peOnly = !ceOnly && s.match("\\b(pe|puts?|bearish|short)\\s+(only|side only|trades only)\\b|\\bonly\\s+(pe|puts?|bearish|short)\\b|\\b(no|skip|without)\\s+(ce|calls?|bullish)\\b", m -> s.set("directions", "PE", m, null)) > 0;
        if (!ceOnly && !peOnly) {
            boolean ce = s.peek("\\b(ce|calls?|bullish)\\b"), pe = s.peek("\\b(pe|puts?|bearish)\\b");
            if (s.match("\\b(both\\s+(directions|sides|ce\\s+and\\s+pe)|ce\\s*(/|and|&)\\s*pe|pe\\s*(/|and|&)\\s*ce)\\b", m -> s.set("directions", "BOTH", m, null)) == 0) {
                if (ce && !pe) s.match("\\b(buy|long)?\\s*(ce|calls?)\\b", m -> s.set("directions", "CE", m, "Only CE (calls) mentioned"));
                else if (pe && !ce) s.match("\\b(buy|long)?\\s*(pe|puts?)\\b", m -> s.set("directions", "PE", m, "Only PE (puts) mentioned"));
            }
            // The previous-day engine defaults to PE (older PE-only strategies): name both sides and it trades both.
            if (ce && pe && prevDay && !s.params.containsKey("directions")) {
                s.params.put("directions", "BOTH");
                s.findings.add(new Finding("directions", "BOTH", "CE and PE", "Both sides: CE above yesterday's high, PE below yesterday's low"));
            }
        }

        // Quantity
        // "lot size 65" is read and ignored: the index sets the lot size, not the strategy.
        s.match("\\blot\\s*size\\s*(of|=|:)?\\s*(\\d+)\\b", m -> {
            boolean bank = "BANKNIFTY".equals(s.params.get("underlying"));
            int size = bank ? 30 : 65;
            if (!m.group(2).equals(String.valueOf(size)))
                s.unsupported(m, "Lot size comes from the index: " + (bank ? "Bank Nifty" : "NIFTY 50") + " uses " + size + ", so " + m.group(2) + " is not used");
            else s.note(m, "Lot size " + size + " (set by the index)");
        });
        s.match("\\b(\\d+|one|two|three|four|five)\\s*lots?\\b(?!\\s*size)", m -> s.setInt("lots", count(m.group(1)), 1, 50, m));
        s.match("\\blots?\\s*(=|:)\\s*(\\d+)\\b", m -> s.setInt("lots", m.group(2), 1, 50, m));

        // Trades per day
        s.match("\\b(max(imum)?|up\\s+to|at\\s+most)?\\s*" + COUNT + "\\s+(trades?|entries|entry)\\s*(per|a|/|each)\\s*day\\b", m -> s.setInt("maxTradesPerDay", count(m.group(3)), 1, 10, m));
        s.match("\\b(trade|enter)\\s+(once|twice)\\s+(a|per)\\s+day\\b", m -> s.setInt("maxTradesPerDay", count(m.group(2)), 1, 10, m));

        // Trailing stop
        s.match("\\btrail(ing)?(\\s+(sl|stop(-loss)?))?[^.;\\n]{0,25}?\\b(\\d{1,2})\\s*[- ]?(min(ute)?s?|m)\\b", m -> s.setInt("trailMinutes", m.group(5), 1, 60, m));
        s.match("\\b(\\d{1,2})\\s*[- ]?(min(ute)?s?|m)\\s+(candle\\s+|block\\s+)?(lows?|highs?|low\\s*/\\s*high|trail(ing)?)\\b", m -> s.setInt("trailMinutes", m.group(1), 1, 60, m));
        s.match("\\btrail(ing)?\\s+(only\\s+)?(after|once)\\s+(\\+?" + NUM + ")\\s*(points?|pts?)(\\s+in\\s+(profit|favou?r))?", m -> s.setNum("trailAfterPoints", m.group(5), 0, 3000, m));
        s.match("\\b(after\\s+every\\s+(new\\s+)?(\\d{1,2})[- ]?min(ute)?\\s+candle\\s+closes,?\\s*)?move\\s+(your\\s+|the\\s+)?(sl|stop)\\s+(up\\s+|down\\s+)?to\\s+the\\s+(low|high)\\s+of\\s+(that|the|each)\\s+(just[- ]closed|completed|last|previous)\\s+candle\\b",
                m -> {
                    if (m.group(3) != null && !s.params.containsKey("trailMinutes")) s.setInt("trailMinutes", m.group(3), 1, 60, m);
                    else s.note(m, "Trailing stop on each completed block's low (CE) / high (PE) (built in)");
                });
        s.match("\\bdelayed\\s+trail\\b", m -> s.note(m, "Delayed trail: give the points with \"trail after N points\""));

        // Target
        s.match("\\btarget(\\s+of)?\\s*(\\+|-)?\\s*" + NUM + "\\s*(points?|pts?)?\\b", m -> s.setNum("targetPoints", m.group(3), 1, 3000, m));
        s.match("\\b" + NUM + "\\s*(points?|pts?)?\\s+target\\b", m -> s.setNum("targetPoints", m.group(1), 1, 3000, m));
        s.match("\\bno\\s+target\\b", m -> s.set("targetPoints", "0", m, "No target"));

        // "never more than 150 points above entry" (PE) / "below entry" (CE): a cap on the initial stop distance.
        // Read before the gap rules, which would otherwise take "60 points below" as a gap size.
        s.match("\\b(but\\s+)?(never|not|no)\\s+more\\s+than\\s+" + NUM + "\\s*(points?|pts?)\\s+(above|below|from|away\\s+from)\\s+(the\\s+)?entry\\b",
                m -> s.setNum("maxStopPoints", m.group(3), 5, 3000, m));

        // "opens >= 0.2% and < 1% away from the previous close": a gap either way, with a minimum and a maximum
        String AWAY = "\\s*(away\\s+)?from\\s+(the\\s+)?(previous|prev|yesterday'?s?)('?s)?\\s+close";
        String MIN = "(>=|≥|>|at\\s+least|more\\s+than|above|over)?\\s*";
        String MAX = "(<=|≤|<|below|under|less\\s+than|up\\s+to)\\s*";
        s.match("\\bopens?\\s+" + MIN + NUM + "\\s*%\\s*(and|but|,)\\s*" + MAX + NUM + "\\s*%" + AWAY,
                m -> { s.set("gapFilter", "ANY_GAP", m, "A gap either way"); s.setNum("gapPct", m.group(2), 0.01, 5, m); s.setNum("maxGapPct", m.group(5), 0.05, 10, m); });
        s.match("\\bopens?\\s+" + MIN + NUM + "\\s*(points?|pts?)\\s*(and|but|,)\\s*" + MAX + NUM + "\\s*(points?|pts?)" + AWAY,
                m -> { s.set("gapFilter", "ANY_GAP", m, "A gap either way"); s.setNum("gapPoints", m.group(2), 1, 2000, m); s.setNum("maxGapPoints", m.group(6), 1, 3000, m); });
        s.match("\\bopens?\\s+" + MIN + NUM + "\\s*%" + AWAY,
                m -> { s.set("gapFilter", "ANY_GAP", m, "A gap either way"); s.setNum("gapPct", m.group(2), 0.01, 5, m); });

        // Gap filter
        boolean up = s.peek("\\bgap(?:s|ped)?[- ]?ups?\\b|\\bopens?\\s+(higher|above)\\b|(%|\\bpoints?|\\bpts?)\\s*(higher|above)\\s+(the\\s+)?(previous|prev|yesterday)"),
                down = s.peek("\\bgap(?:s|ped)?[- ]?downs?\\b|\\bopens?\\s+(lower|below)\\b|(%|\\bpoints?|\\bpts?)\\s*(lower|below)\\s+(the\\s+)?(previous|prev|yesterday)");
        String gapMax = "(gap\\s*)?(below|under|less\\s+than|<|up\\s+to)\\s*" + NUM + "\\s*%";
        // Gap sizes in points (NIFTY points) come first: matches consume text, and "%" patterns don't take points.
        String PTS = "\\s*(points?|pts?)\\b";
        s.match("\\b(skip|avoid|no\\s+trades?\\s+(on|when))[^.;\\n]{0,20}\\bgaps?\\s*(above|over|more\\s+than|>|≥|>=|of)?\\s*" + NUM + PTS, m -> s.setNum("maxGapPoints", m.group(4), 1, 2000, m));
        s.match("\\bgap\\s*(<|below|under|less\\s+than)\\s*" + NUM + PTS, m -> s.setNum("maxGapPoints", m.group(2), 1, 2000, m));
        s.match("\\b(skip|avoid|no\\s+trades?\\s+(on|when))[^.;\\n]{0,20}\\bgaps?\\s*(above|over|more\\s+than|>|≥|>=|of)?\\s*" + NUM + "\\s*%", m -> s.setNum("maxGapPct", m.group(4), 0.05, 10, m));
        s.match("\\bgap\\s*(<|below|under|less\\s+than)\\s*" + NUM + "\\s*%", m -> s.setNum("maxGapPct", m.group(2), 0.05, 10, m));
        if (s.match("\\b(flat|no[- ]gap)\\s+(open(s|ing)?|days?)\\b", m -> s.set("gapFilter", "FLAT", m, null)) == 0) {
            String filter = up && down ? "ANY_GAP" : up ? "GAP_UP" : down ? "GAP_DOWN" : null;
            if (filter != null) {
                s.match("\\b(gap(?:s|ped)?[- ]?(ups?|downs?)|opens?\\s+(higher|lower|above|below))(\\s+days?)?\\s+(of|by|above|over|more\\s+than|at\\s+least|≥|>=|>)?\\s*" + NUM + PTS + "(\\s+(above|below)\\s+(the\\s+)?(previous|prev|yesterday'?s?)\\s+close)?",
                        m -> { s.set("gapFilter", filter, m, null); s.setNum("gapPoints", m.group(6), 1, 1000, m); });
                s.match("\\b(opens?\\s+)?(at\\s+least\\s+|≥\\s*|>=\\s*)?" + NUM + PTS + "\\s*(higher|above|lower|below)(\\s+(the\\s+)?(previous|prev|yesterday'?s?)('?s)?\\s+close)?",
                        m -> { s.params.putIfAbsent("gapFilter", filter); s.setNum("gapPoints", m.group(3), 1, 1000, m); });
                s.match("\\b(gap(?:s|ped)?[- ]?(ups?|downs?)|opens?\\s+(higher|lower|above|below))(\\s+days?)?(\\s+(of|by|above|over|more\\s+than|at\\s+least|≥|>=|>)?\\s*" + NUM + "\\s*%)?(\\s+(above|below)\\s+(the\\s+)?(previous|prev|yesterday'?s?)\\s+close)?",
                        m -> {
                            s.set("gapFilter", filter, m, null);
                            if (m.group(7) != null) s.setNum("gapPct", m.group(7), 0.01, 5, m);
                        });
                s.match("\\b(opens?\\s+)?(at\\s+least\\s+|≥\\s*|>=\\s*)?" + NUM + "\\s*%\\s*(higher|above|lower|below)(\\s+(the\\s+)?(previous|prev|yesterday'?s?)('?s)?\\s+close)?",
                        m -> { s.params.putIfAbsent("gapFilter", filter); s.setNum("gapPct", m.group(3), 0.01, 5, m); });
                s.match("\\b(at\\s+least|above|over|more\\s+than|≥|>=)\\s*" + NUM + "\\s*%\\s*(gap|above|below)", m -> s.setNum("gapPct", m.group(2), 0.01, 5, m));
                s.match("\\b" + NUM + "\\s*%\\s*(\\+\\s*)?(gap|above\\s+(the\\s+)?(previous|prev)|below\\s+(the\\s+)?(previous|prev))", m -> s.setNum("gapPct", m.group(1), 0.01, 5, m));
            }
        }

        if (cpr || emaCross) {
            // These engines take their stop from the pivot / the last candles and trade any opening: drop what they ignore.
            for (String k : List.of("gapFilter", "gapPct", "gapPoints", "maxGapPct", "maxGapPoints", "initialStop", "referenceMinutes"))
                if (s.params.remove(k) != null)
                    s.warnings.add("\"" + k + "\" is not used by the " + (cpr ? "CPR" : "EMA crossover") + " strategy and was dropped");
        }

        if ("GAP_FILL".equals(s.params.get("referenceMode"))) {
            // The gap-fill engine trades both gap directions by itself and stops at the 09:15 candle: drop what it ignores.
            String gf = s.params.remove("gapFilter");
            s.params.remove("initialStop");
            // No side named: gap-up days trade PE back down, gap-down days CE back up.
            if (!s.params.containsKey("directions") && "GAP_UP".equals(gf)) s.params.put("directions", "PE");
            if (!s.params.containsKey("directions") && "GAP_DOWN".equals(gf)) s.params.put("directions", "CE");
            if (s.params.containsKey("gapPoints") || s.params.containsKey("maxGapPoints"))
                s.warnings.add("The gap-fill strategy measures the gap in % of yesterday's close: write e.g. \"gap-up above 0.2%\" instead of points");
        }

        // Gap sizes in words only: explain which number is used / needed.
        s.match("\\b(there\\s+must\\s+be\\s+)?(a\\s+)?(minimum|min|small|modest|minimum\\s*/\\s*modest)\\s+gap([- ]up|[- ]down)?(\\s+to\\s+proceed)?\\b|\\bonly\\s+proceed\\s+if\\s+it'?s\\s+a\\s+(minimum|small|modest)(\\s*/\\s*(minimum|small|modest))?\\s+gap\\b",
                m -> { if (!s.params.containsKey("gapPct")) s.note(m, "Minimum gap with no number: Gap % keeps its default 0.2% (write e.g. \"gap up above 0.3%\" to change it)"); else s.note(m, "Minimum gap"); });
        s.match("\\b(is\\s+the\\s+gap\\s+too\\s+big|if\\s+the\\s+gap\\s+is\\s+(very\\s+|too\\s+)?(large|big|huge))\\b[^.;\\n]*?(skip\\s+the\\s+trade(\\s+entirely)?(\\s*\\([^)]*\\))?)?|\\bgap\\s+(is\\s+)?too\\s+big\\b",
                m -> { if (!s.params.containsKey("maxGapPct")) s.unsupported(m, "Large-gap skip needs a number: write e.g. \"skip gaps above 1%\" to set Max gap % (not set)"); });

        // Entry window
        s.match("\\b(entr(y|ies)|enter|trades?)\\s+(only\\s+)?(from|between)\\s+" + TIME + "\\s*(until|till|to|and|-|–)\\s*" + TIME,
                m -> { s.setTime("entryFrom", m.group(5), m); s.setTime("entryUntil", m.group(7), m); });
        s.match("\\b(no\\s+(new\\s+)?(entr(y|ies)|trades?)|don'?t\\s+enter|do\\s+not\\s+enter|stop\\s+(taking\\s+)?entries)\\s+(after|from|beyond)\\s+" + TIME, m -> s.setTime("entryUntil", m.group(7), m));
        // "No entries before 09:45" is a start time; read it before "entries before 14:00" takes it as a cut-off.
        s.match("\\b(no\\s+(entr(y|ies)|trades?)|don'?t\\s+enter)\\s+before\\s+" + TIME, m -> s.setTime("entryFrom", m.group(4), m));
        s.match("\\b(entr(y|ies)|enter|trades?)\\s+(only\\s+)?(until|till|before|by)\\s+" + TIME, m -> s.setTime("entryUntil", m.group(5), m));
        s.match("\\b(on\\s+)?(5[- ]?min(ute)?\\s+)?(candles?|bars?)\\s+(starting|that\\s+start|which\\s+start)\\s+(before|by)\\s+" + TIME, m -> s.setTime("entryUntil", m.group(7), m));
        s.match("\\b(on\\s+)?(5[- ]?min(ute)?\\s+)?(candles?|bars?)\\s+(starting|that\\s+start|which\\s+start)\\s+(at\\s+or\\s+after|from|after)\\s+" + TIME, m -> s.setTime("entryFrom", m.group(7), m));
        s.match("\\b(entr(y|ies)|enter|trades?)\\s+(only\\s+)?(from|after|not\\s+before)\\s+" + TIME, m -> s.setTime("entryFrom", m.group(5), m));
        s.match("\\b(wait|start)\\s+(until|till|from)\\s+" + TIME, m -> s.setTime("entryFrom", m.group(3), m));

        // Breakeven, failed breakout, stop cap
        s.match("\\b(move\\s+(the\\s+)?(sl|stop)\\s+to\\s+)?break[- ]?even(\\s+on\\s+trail)?\\b", m -> s.set("breakevenOnTrail", "true", m, null));
        s.match("\\block(ing)?\\s+(in\\s+)?\\+?" + NUM + "\\s*(points?|pts?)", m -> s.setNum("breakevenLockPoints", m.group(3), 0, 1000, m));
        s.match("\\bfailed[- ]break(out|down)(\\s+exit)?(\\s+(of|at|after))?\\s*" + NUM + "\\s*(points?|pts?)?", m -> s.setNum("failedBreakoutPoints", m.group(5), 1, 1000, m));
        s.match("\\bfailed[- ]break(out|down)(\\s+exit)?\\b", m -> s.note(m, "Failed-breakout exit: give the points, e.g. \"failed breakout exit 10 points\""));
        s.match("\\b" + NUM + "\\s*(points?|pts?)\\s+(sl|stop)\\s+cap\\b|\\b(max(imum)?\\s+(sl|stop)(\\s+distance)?|(sl|stop)\\s+cap)\\s*(of)?\\s*" + NUM + "\\s*(points?|pts?)?", m -> s.setNum("maxStopPoints", m.group(1) != null ? m.group(1) : m.group(10), 5, 3000, m));

        // Weekdays to skip
        s.match("\\b(skip|avoid|no\\s+trades?\\s+on|don'?t\\s+trade\\s+on)\\s+((mon|tue|wed|thu|fri)[a-z]*(\\s*(,|and|&|/)\\s*(mon|tue|wed|thu|fri)[a-z]*)*)", m -> {
            List<String> days = new ArrayList<>();
            Matcher d = Pattern.compile("(mon|tue|wed|thu|fri)", F).matcher(m.group(2));
            while (d.find()) days.add(d.group(1).toUpperCase(Locale.ROOT));
            s.set("skipDays", String.join(",", new LinkedHashSet<>(days)), m, null);
        });

        // Charges
        s.match("\\b(charges|costs?|brokerage|fees?)\\s*(of|=|:)?\\s*(rs\\.?|inr|₹)?\\s*" + NUM + "\\s*(per\\s+(trade|round[- ]?trip))?", m -> s.setNum("costPerTrade", m.group(4), 0, 5000, m));
        s.match("(rs\\.?\\s*|inr\\s*|₹\\s*)" + NUM + "\\s*(charges|costs?|brokerage|fees?)(\\s+per\\s+(trade|round[- ]?trip))?", m -> s.setNum("costPerTrade", m.group(2), 0, 5000, m));
    }

    // ── Indicator strategies ─────────────────────────────────────────────────

    private void sma(Scan s) {
        s.match("\\b(\\d+)\\s*/\\s*(\\d+)\\s*(sma|ma|moving\\s+average)?", m -> { s.setInt("fast", m.group(1), 2, 400, m); s.setInt("slow", m.group(2), 2, 400, m); });
        s.match("\\bfast\\s*(sma|ma)?\\s*(of|=|:)?\\s*(\\d+)", m -> s.setInt("fast", m.group(3), 2, 400, m));
        s.match("\\bslow\\s*(sma|ma)?\\s*(of|=|:)?\\s*(\\d+)", m -> s.setInt("slow", m.group(3), 2, 400, m));
        s.builtin("\\b(sma|simple moving average|moving[- ]average)\\s*cross(over)?\\b", "SMA crossover");
        if (s.params.containsKey("fast") && s.params.containsKey("slow")
                && Integer.parseInt(s.params.get("fast")) >= Integer.parseInt(s.params.get("slow")))
            s.warnings.add("The fast SMA should be shorter than the slow SMA");
    }

    private void rsi(Scan s) {
        s.match("\\brsi\\s*(\\(|period)?\\s*(\\d+)\\)?", m -> s.setInt("period", m.group(2), 2, 100, m));
        s.match("\\boversold\\s*(at|=|:|below|<)?\\s*(\\d+)|\\b(\\d+)\\s*oversold", m -> s.setInt("oversold", m.group(2) != null ? m.group(2) : m.group(3), 1, 50, m));
        s.match("\\boverbought\\s*(at|=|:|above|>)?\\s*(\\d+)|\\b(\\d+)\\s*overbought", m -> s.setInt("overbought", m.group(2) != null ? m.group(2) : m.group(3), 50, 99, m));
    }

    private void macd(Scan s) {
        s.match("\\bmacd\\s*\\(?\\s*(\\d+)\\s*[,/ ]\\s*(\\d+)\\s*[,/ ]\\s*(\\d+)\\s*\\)?", m -> {
            s.setInt("fast", m.group(1), 2, 100, m); s.setInt("slow", m.group(2), 2, 200, m); s.setInt("signal", m.group(3), 2, 100, m);
        });
        s.builtin("\\bmacd\\b(\\s+signal)?(\\s+cross(over)?)?", "MACD signal crossover");
    }

    // ── Name ─────────────────────────────────────────────────────────────────

    /** "1 lot", "3 lots". */
    static String lots(String n) { return n + ("1".equals(n.trim()) ? " lot" : " lots"); }

    private static String suggestName(String type, Map<String, String> p) {
        if (!type.equals("NIFTY_OPENING_BREAKOUT")) return switch (type) {
            case "SMA_CROSSOVER" -> "SMA " + p.getOrDefault("fast", "10") + "/" + p.getOrDefault("slow", "30") + " crossover";
            case "RSI_THRESHOLD" -> "RSI " + p.getOrDefault("period", "14") + " " + p.getOrDefault("oversold", "30") + "/" + p.getOrDefault("overbought", "70");
            default -> "MACD signal";
        };
        StringBuilder n = new StringBuilder("BANKNIFTY".equals(p.get("underlying")) ? "Bank Nifty " : "Nifty ");
        String dir = p.getOrDefault("directions", "BOTH");
        n.append(dir.equals("BOTH") || dir.equals("WITH_GAP") ? "CE/PE" : dir);
        switch (p.getOrDefault("gapFilter", "NONE")) {
            case "GAP_UP" -> n.append(" Gap-Up");
            case "GAP_DOWN" -> n.append(" Gap-Down");
            case "ANY_GAP" -> n.append(" Gap-Day");
            case "FLAT" -> n.append(" Flat-Open");
            default -> { }
        }
        if ("PREVIOUS_DAY".equals(p.get("referenceMode"))) {
            n.append(" Prev-Day");
            if (p.containsKey("narrowRangeDays")) n.append(" NR").append(p.get("narrowRangeDays"));
            if (p.containsKey("excludeNarrowRangeDays")) n.append(" not NR").append(p.get("excludeNarrowRangeDays"));
        }
        else if ("CPR".equals(p.get("referenceMode")) || "EMA_CROSS".equals(p.get("referenceMode"))) {
            if ("CPR".equals(p.get("referenceMode")))
                n.append(p.containsKey("maxCprWidthPct") ? " Narrow-CPR" : " CPR").append(" Breakout");
            else n.append(" EMA ").append(p.getOrDefault("fastEma", "9")).append("/").append(p.getOrDefault("slowEma", "21")).append(" Cross");
            if (p.containsKey("targetPoints") && !p.get("targetPoints").equals("0")) n.append(" ").append(trim(p.get("targetPoints"))).append(" Target");
            if (p.containsKey("maxStopPoints")) n.append(" - stop cap ").append(trim(p.get("maxStopPoints")));
            if (p.containsKey("lots")) n.append(" - ").append(lots(p.get("lots")));
            return n.toString();
        }
        else if ("GAP_FILL".equals(p.get("referenceMode"))) {
            n.append(dir.equals("PE") ? " Gap-Up Fill" : dir.equals("CE") ? " Gap-Down Fill" : " Gap Fill");
            String f = p.getOrDefault("gapFillTarget", "0.5");
            n.append(f.equals("1") ? " Full" : " " + Math.round(Double.parseDouble(f) * 100) + "%");
            if (p.containsKey("maxStopPoints")) n.append(" - stop cap ").append(trim(p.get("maxStopPoints")));
            if (p.containsKey("lots")) n.append(" - ").append(lots(p.get("lots")));
            return n.toString();
        }
        else if (p.containsKey("referenceMinutes") && !p.get("referenceMinutes").equals("5")) n.append(" ").append(p.get("referenceMinutes")).append("-min");
        n.append(dir.equals("PE") ? " Breakdown" : " Breakout");
        if (p.containsKey("targetPoints") && !p.get("targetPoints").equals("0")) n.append(" ").append(trim(p.get("targetPoints"))).append(" Target");
        else if (p.containsKey("targetR")) n.append(" ").append(trim(p.get("targetR"))).append("R Target");
        if (p.containsKey("maxStopPoints")) n.append(" - stop cap ").append(trim(p.get("maxStopPoints")));
        if (p.containsKey("lots")) n.append(" - ").append(lots(p.get("lots")));
        return n.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** The first group among these that matched. */
    private static String first(Matcher m, int... groups) {
        for (int g : groups) if (m.group(g) != null) return m.group(g);
        return "";
    }

    private static String count(String word) {
        Integer w = WORDS.get(word.toLowerCase(Locale.ROOT));
        return w == null ? word : String.valueOf(w);
    }

    private static String trim(String num) {
        return num.endsWith(".0") ? num.substring(0, num.length() - 2) : num;
    }

    /** Text being parsed, with the parts already used blanked out so each phrase feeds one rule. */
    private static final class Scan {
        final String original;
        final StringBuilder work;
        final boolean[] used;
        final Map<String, String> params = new LinkedHashMap<>();
        final List<Finding> findings = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        Scan(String text) {
            original = text;
            work = new StringBuilder(text);
            used = new boolean[text.length()];
        }

        boolean find(String regex)  { return Pattern.compile(regex, F).matcher(original).find(); }
        boolean peek(String regex)  { return Pattern.compile(regex, F).matcher(work).find(); }

        /** Runs the handler for each match in the not-yet-used text; returns how many matched. */
        int match(String regex, java.util.function.Consumer<Matcher> handler) {
            Matcher m = Pattern.compile(regex, F).matcher(work.toString());
            int n = 0;
            List<int[]> spans = new ArrayList<>();
            while (m.find()) {
                if (m.end() == m.start()) continue;
                handler.accept(m);
                spans.add(new int[]{m.start(), m.end()});
                n++;
            }
            for (int[] sp : spans) consume(sp[0], sp[1]);
            return n;
        }

        void builtin(String regex, String note) {
            match(regex, m -> {
                String n = note + " (built in)";
                if (findings.stream().noneMatch(f -> f.key() == null && n.equals(f.note()))) findings.add(new Finding(null, null, text(m), n));
            });
        }

        /** Words that only structure the text (headings, "Step 3 —", "if yes →"): used, but no finding. */
        void ignore(String regex) { match(regex, m -> { }); }

        /** A rule the strategy type cannot apply: shown as a warning so nobody assumes it will be followed. */
        void unsupported(Matcher m, String why) {
            if (warnings.stream().noneMatch(w -> w.startsWith(why))) warnings.add(why + " (\"" + text(m) + "\")");
        }

        void note(Matcher m, String note) { findings.add(new Finding(null, null, text(m), note)); }

        void set(String key, String value, Matcher m, String note) {
            String prev = params.put(key, value);
            if (prev != null && !prev.equals(value))
                warnings.add(key + " was described twice (" + prev + " and " + value + "); using " + value);
            findings.add(new Finding(key, value, text(m), note));
        }

        void setInt(String key, String raw, int min, int max, Matcher m) {
            int v;
            try { v = Integer.parseInt(raw); } catch (NumberFormatException e) { warnings.add(key + ": not a whole number: " + raw); return; }
            if (v < min || v > max) { warnings.add(key + " " + v + " is outside " + min + "–" + max + " (\"" + text(m) + "\"); not set"); return; }
            set(key, String.valueOf(v), m, null);
        }

        void setNum(String key, String raw, double min, double max, Matcher m) {
            double v;
            try { v = Double.parseDouble(raw); } catch (NumberFormatException e) { warnings.add(key + ": not a number: " + raw); return; }
            if (v < min || v > max) { warnings.add(key + " " + trim(raw) + " is outside " + trim(String.valueOf(min)) + "–" + trim(String.valueOf(max)) + " (\"" + text(m) + "\"); not set"); return; }
            set(key, trim(raw), m, null);
        }

        void setTime(String key, String raw, Matcher m) {
            LocalTime t = time(raw);
            if (t == null) { warnings.add(key + ": couldn't read the time \"" + raw.trim() + "\""); return; }
            if (t.isBefore(LocalTime.of(9, 15)) || t.isAfter(LocalTime.of(15, 30))) {
                warnings.add(key + " " + t + " is outside market hours 09:15–15:30; not set"); return;
            }
            set(key, t.toString(), m, null);
        }

        /** The reference range length in minutes: a multiple of 5 from 5 to 60 (the engine's limits). */
        void setReferenceMinutes(String raw, Matcher m) {
            int v;
            try { v = Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { warnings.add("referenceMinutes: not a whole number: " + raw); return; }
            if (v < 5 || v > 60 || v % 5 != 0) { warnings.add("Reference range " + v + " minutes must be a multiple of 5 from 5 to 60 (\"" + text(m) + "\"); not set"); return; }
            if (v == 5) { findings.add(new Finding(null, null, text(m), "Reference candle 09:15–09:20 IST (built in)")); return; }
            set("referenceMinutes", String.valueOf(v), m, "Reference range " + v + " minutes from 09:15");
        }

        void setDate(String key, String raw, Matcher m) {
            LocalDate d = date(raw);
            if (d == null) { warnings.add(key + ": couldn't read the date \"" + raw.trim() + "\""); return; }
            set(key, d.toString(), m, "Trades on " + d + " only");
        }

        private static LocalDate date(String raw) {
            String r = raw.trim();
            for (String f : new String[]{"yyyy-MM-dd", "d-M-yyyy", "d/M/yyyy", "d MMM yyyy", "d MMMM yyyy"}) {
                try {
                    return LocalDate.parse(r, new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
                            .appendPattern(f).toFormatter(Locale.ENGLISH));
                } catch (java.time.format.DateTimeParseException ignored) { }
            }
            return null;
        }

        private static LocalTime time(String raw) {
            Matcher m = Pattern.compile("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?", F).matcher(raw.trim());
            if (!m.matches()) return null;
            int h = Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            String ap = m.group(3) == null ? null : m.group(3).toLowerCase(Locale.ROOT);
            if (ap != null) { if (h < 1 || h > 12) return null; h = h % 12 + (ap.equals("pm") ? 12 : 0); }
            else if (h >= 1 && h <= 3) h += 12;   // "until 2" on a trading day means 14:00
            if (h > 23 || min > 59) return null;
            return LocalTime.of(h, min);
        }

        String text(Matcher m) { return original.substring(m.start(), m.end()).trim(); }

        private void consume(int from, int to) {
            for (int i = from; i < to; i++) { used[i] = true; work.setCharAt(i, ' '); }
        }

        /** Sentences and clauses where no rule matched anything. */
        List<String> leftovers() {
            List<String> out = new ArrayList<>();
            Matcher clause = Pattern.compile("(?:[^.;\\n,]|[.,](?=\\d))+").matcher(original);   // keep "0.2%" and "1,000" whole
            while (clause.find()) {
                String c = clause.group().trim();
                if (c.isEmpty() || FILLER.matcher(c).matches()) continue;
                boolean any = false;
                for (int i = clause.start(); i < clause.end() && !any; i++) any = used[i];
                if (!any) out.add(c);
            }
            return out;
        }
    }
}
