package com.tradeanalysis.strategy.describe;

import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.strategy.engine.NiftyOpeningBreakoutEngine;
import org.springframework.stereotype.Component;

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

        switch (type) {
            case "SMA_CROSSOVER" -> sma(scan);
            case "RSI_THRESHOLD" -> rsi(scan);
            case "MACD_SIGNAL" -> macd(scan);
            default -> breakout(scan);
        }

        if (type.equals("NIFTY_OPENING_BREAKOUT")) {
            try {
                NiftyOpeningBreakoutEngine.validateParams(new Params(new HashMap<>(scan.params)));
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

        // Initial stop at the reference candle's own low (CE) / high (PE) = the OPPOSITE side.
        s.match("\\b(note|mark)\\s+its\\s+(low|high)\\b[^.;\\n]{0,40}\\binitial\\s+(sl|stop(?:[- ]loss)?)\\b"
                + "|\\b(sl|stop(?:[- ]loss)?)\\s+(at|on|below|above)\\s+(the\\s+)?(low|high)\\s+of\\s+(the\\s+)?(first|reference|opening|9[:.]15)\\b[^.;\\n]{0,40}?candle(\\s*\\(?\\s*9[:.]15\\s+candle\\s+(low|high)\\s*\\)?)?"
                + "|\\b(sl|stop(?:[- ]loss)?)\\s+(at|on|below|above)\\s+(the\\s+)?(9[:.]15|first|reference)\\s+candle'?s?\\s+(low|high)\\b",
                m -> s.set("initialStop", "OPPOSITE", m, "Initial SL at the reference candle's low (CE) / high (PE)"));

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
        }

        // Quantity
        s.match("\\blot\\s*size\\s*(of|=|:)?\\s*(\\d+)\\b", m -> s.setInt("lotSize", m.group(2), 1, 1800, m));
        s.match("\\b(\\d+|one|two|three|four|five)\\s*lots?\\b(?!\\s*size)", m -> s.setInt("lots", count(m.group(1)), 1, 50, m));
        s.match("\\blots?\\s*(=|:)\\s*(\\d+)\\b", m -> s.setInt("lots", m.group(2), 1, 50, m));

        // Trades per day
        s.match("\\b(max(imum)?|up\\s+to|at\\s+most)?\\s*" + COUNT + "\\s+(trades?|entries|entry)\\s*(per|a|/|each)\\s*day\\b", m -> s.setInt("maxTradesPerDay", count(m.group(3)), 1, 10, m));
        s.match("\\b(trade|enter)\\s+(once|twice)\\s+(a|per)\\s+day\\b", m -> s.setInt("maxTradesPerDay", count(m.group(2)), 1, 10, m));

        // Trailing stop
        s.match("\\btrail(ing)?(\\s+(sl|stop(-loss)?))?[^.;\\n]{0,25}?\\b(\\d{1,2})\\s*[- ]?(min(ute)?s?|m)\\b", m -> s.setInt("trailMinutes", m.group(5), 1, 60, m));
        s.match("\\b(\\d{1,2})\\s*[- ]?(min(ute)?s?|m)\\s+(candle\\s+|block\\s+)?(lows?|highs?|low\\s*/\\s*high|trail(ing)?)\\b", m -> s.setInt("trailMinutes", m.group(1), 1, 60, m));
        s.match("\\btrail(ing)?\\s+(only\\s+)?(after|once)\\s+(\\+?" + NUM + ")\\s*(points?|pts?)(\\s+in\\s+(profit|favou?r))?", m -> s.setNum("trailAfterPoints", m.group(5), 0, 500, m));
        s.match("\\b(after\\s+every\\s+(new\\s+)?(\\d{1,2})[- ]?min(ute)?\\s+candle\\s+closes,?\\s*)?move\\s+(your\\s+|the\\s+)?(sl|stop)\\s+(up\\s+|down\\s+)?to\\s+the\\s+(low|high)\\s+of\\s+(that|the|each)\\s+(just[- ]closed|completed|last|previous)\\s+candle\\b",
                m -> {
                    if (m.group(3) != null && !s.params.containsKey("trailMinutes")) s.setInt("trailMinutes", m.group(3), 1, 60, m);
                    else s.note(m, "Trailing stop on each completed block's low (CE) / high (PE) (built in)");
                });
        s.match("\\bdelayed\\s+trail\\b", m -> s.note(m, "Delayed trail: give the points with \"trail after N points\""));

        // Target
        s.match("\\btarget(\\s+of)?\\s*(\\+|-)?\\s*" + NUM + "\\s*(points?|pts?)?\\b", m -> s.setNum("targetPoints", m.group(3), 1, 1000, m));
        s.match("\\b" + NUM + "\\s*(points?|pts?)?\\s+target\\b", m -> s.setNum("targetPoints", m.group(1), 1, 1000, m));
        s.match("\\bno\\s+target\\b", m -> s.set("targetPoints", "0", m, "No target"));

        // Gap filter
        boolean up = s.peek("\\bgap(?:s|ped)?[- ]?ups?\\b|\\bopens?\\s+(higher|above)\\b|%\\s*(higher|above)\\s+(the\\s+)?(previous|prev|yesterday)"),
                down = s.peek("\\bgap(?:s|ped)?[- ]?downs?\\b|\\bopens?\\s+(lower|below)\\b|%\\s*(lower|below)\\s+(the\\s+)?(previous|prev|yesterday)");
        String gapMax = "(gap\\s*)?(below|under|less\\s+than|<|up\\s+to)\\s*" + NUM + "\\s*%";
        s.match("\\b(skip|avoid|no\\s+trades?\\s+(on|when))[^.;\\n]{0,20}\\bgaps?\\s*(above|over|more\\s+than|>|≥|>=|of)?\\s*" + NUM + "\\s*%", m -> s.setNum("maxGapPct", m.group(4), 0.05, 10, m));
        s.match("\\bgap\\s*(<|below|under|less\\s+than)\\s*" + NUM + "\\s*%", m -> s.setNum("maxGapPct", m.group(2), 0.05, 10, m));
        if (s.match("\\b(flat|no[- ]gap)\\s+(open(s|ing)?|days?)\\b", m -> s.set("gapFilter", "FLAT", m, null)) == 0) {
            String filter = up && down ? "ANY_GAP" : up ? "GAP_UP" : down ? "GAP_DOWN" : null;
            if (filter != null) {
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

        // Gap sizes in words only: explain which number is used / needed.
        s.match("\\b(there\\s+must\\s+be\\s+)?(a\\s+)?(minimum|min|small|modest|minimum\\s*/\\s*modest)\\s+gap([- ]up|[- ]down)?(\\s+to\\s+proceed)?\\b|\\bonly\\s+proceed\\s+if\\s+it'?s\\s+a\\s+(minimum|small|modest)(\\s*/\\s*(minimum|small|modest))?\\s+gap\\b",
                m -> { if (!s.params.containsKey("gapPct")) s.note(m, "Minimum gap with no number: Gap % keeps its default 0.2% (write e.g. \"gap up above 0.3%\" to change it)"); else s.note(m, "Minimum gap"); });
        s.match("\\b(is\\s+the\\s+gap\\s+too\\s+big|if\\s+the\\s+gap\\s+is\\s+(very\\s+|too\\s+)?(large|big|huge))\\b[^.;\\n]*?(skip\\s+the\\s+trade(\\s+entirely)?(\\s*\\([^)]*\\))?)?|\\bgap\\s+(is\\s+)?too\\s+big\\b",
                m -> { if (!s.params.containsKey("maxGapPct")) s.unsupported(m, "Large-gap skip needs a number: write e.g. \"skip gaps above 1%\" to set Max gap % (not set)"); });

        // Entry window
        s.match("\\b(entr(y|ies)|enter|trades?)\\s+(only\\s+)?(from|between)\\s+" + TIME + "\\s*(until|till|to|and|-|–)\\s*" + TIME,
                m -> { s.setTime("entryFrom", m.group(5), m); s.setTime("entryUntil", m.group(7), m); });
        s.match("\\b(no\\s+(new\\s+)?(entr(y|ies)|trades?)|don'?t\\s+enter|do\\s+not\\s+enter|stop\\s+(taking\\s+)?entries)\\s+(after|from|beyond)\\s+" + TIME, m -> s.setTime("entryUntil", m.group(7), m));
        s.match("\\b(entr(y|ies)|enter|trades?)\\s+(only\\s+)?(until|till|before|by)\\s+" + TIME, m -> s.setTime("entryUntil", m.group(5), m));
        s.match("\\b(entr(y|ies)|enter|trades?)\\s+(only\\s+)?(from|after|not\\s+before)\\s+" + TIME, m -> s.setTime("entryFrom", m.group(5), m));
        s.match("\\b(no\\s+(entr(y|ies)|trades?)|don'?t\\s+enter)\\s+before\\s+" + TIME, m -> s.setTime("entryFrom", m.group(4), m));
        s.match("\\b(wait|start)\\s+(until|till|from)\\s+" + TIME, m -> s.setTime("entryFrom", m.group(3), m));

        // Breakeven, failed breakout, stop cap
        s.match("\\b(move\\s+(the\\s+)?(sl|stop)\\s+to\\s+)?break[- ]?even(\\s+on\\s+trail)?\\b", m -> s.set("breakevenOnTrail", "true", m, null));
        s.match("\\block(ing)?\\s+(in\\s+)?\\+?" + NUM + "\\s*(points?|pts?)", m -> s.setNum("breakevenLockPoints", m.group(3), 0, 200, m));
        s.match("\\bfailed[- ]break(out|down)(\\s+exit)?(\\s+(of|at|after))?\\s*" + NUM + "\\s*(points?|pts?)?", m -> s.setNum("failedBreakoutPoints", m.group(5), 1, 300, m));
        s.match("\\bfailed[- ]break(out|down)(\\s+exit)?\\b", m -> s.note(m, "Failed-breakout exit: give the points, e.g. \"failed breakout exit 10 points\""));
        s.match("\\b" + NUM + "\\s*(points?|pts?)\\s+(sl|stop)\\s+cap\\b|\\b(max(imum)?\\s+(sl|stop)(\\s+distance)?|(sl|stop)\\s+cap)\\s*(of)?\\s*" + NUM + "\\s*(points?|pts?)?", m -> s.setNum("maxStopPoints", m.group(1) != null ? m.group(1) : m.group(10), 5, 500, m));

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

    private static String suggestName(String type, Map<String, String> p) {
        if (!type.equals("NIFTY_OPENING_BREAKOUT")) return switch (type) {
            case "SMA_CROSSOVER" -> "SMA " + p.getOrDefault("fast", "10") + "/" + p.getOrDefault("slow", "30") + " crossover";
            case "RSI_THRESHOLD" -> "RSI " + p.getOrDefault("period", "14") + " " + p.getOrDefault("oversold", "30") + "/" + p.getOrDefault("overbought", "70");
            default -> "MACD signal";
        };
        StringBuilder n = new StringBuilder("Nifty ");
        String dir = p.getOrDefault("directions", "BOTH");
        n.append(dir.equals("BOTH") ? "CE/PE" : dir);
        switch (p.getOrDefault("gapFilter", "NONE")) {
            case "GAP_UP" -> n.append(" Gap-Up");
            case "GAP_DOWN" -> n.append(" Gap-Down");
            case "ANY_GAP" -> n.append(" Gap-Day");
            case "FLAT" -> n.append(" Flat-Open");
            default -> { }
        }
        n.append(dir.equals("PE") ? " Breakdown" : " Breakout");
        if (p.containsKey("targetPoints") && !p.get("targetPoints").equals("0")) n.append(" ").append(trim(p.get("targetPoints"))).append(" Target");
        if (p.containsKey("lots")) n.append(" - ").append(p.get("lots")).append(" lots");
        return n.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

        private String text(Matcher m) { return original.substring(m.start(), m.end()).trim(); }

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
