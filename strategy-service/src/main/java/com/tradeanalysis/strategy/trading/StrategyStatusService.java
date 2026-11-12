package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.common.dto.Candle;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.market.Underlying;
import com.tradeanalysis.strategy.engine.MarketDataClient;
import com.tradeanalysis.strategy.engine.NiftyOpeningBreakoutEngine;
import com.tradeanalysis.strategy.model.Strategy;
import com.tradeanalysis.strategy.service.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * What each of a user's PAPER/LIVE strategies is doing today, for the Trading page: in a trade (with its stop), done
 * (with today's P&L), waiting for a signal (with the levels that would trigger it and how far the index is from them),
 * or not trading today (with the engine's reason). The engine run per strategy is cached for a minute.
 */
@Service
public class StrategyStatusService {

    private static final Logger log = LoggerFactory.getLogger(StrategyStatusService.class);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration CACHE = Duration.ofSeconds(60);
    /** Engine reasons that mean "no signal yet" rather than "no trade today". */
    private static final Pattern NOT_YET = Pattern.compile("(?i)no (fresh )?breakout|no close beyond|no ema|before the entry cutoff|in the entry window");

    public enum State { IN_TRADE, DONE, WAITING, NO_TRADE_TODAY, PROBLEM }
    public record Trigger(String side, double level, double distance) {}
    public record Position(String optionType, String tradingsymbol, double entrySpot, Double stop, Double target, Double entryPrice,
                           int quantity, Instant openedAt) {}
    public record Status(Long strategyId, String name, TradingMode mode, String underlying, State state, String note, Double spot,
                         List<Trigger> triggers, Position position, Double todayPnl, LocalTime entryFrom, LocalTime entryUntil) {}
    /** A 5-minute candle of the index, for the Trading page chart. */
    public record Bar(Instant t, double o, double h, double l, double c) {}
    /** The Trading page's live view: the Nifty 50 chart for today and every PAPER/LIVE strategy's status. */
    public record Today(String index, Double spot, Double previousClose, List<Bar> bars, List<Status> strategies) {}

    record Engine(Instant at, Double spot, List<Trigger> triggers, String dayStatus, String dayReason, String problem) {}

    private final StrategyLiveConfigRepository configs;
    private final LivePositionRepository positions;
    private final StrategyService strategies;
    private final MarketDataClient marketData;
    private final NiftyOpeningBreakoutEngine engine;
    private final Clock clock;
    private final Map<Long, Engine> cache = new ConcurrentHashMap<>();

    public StrategyStatusService(StrategyLiveConfigRepository configs, LivePositionRepository positions, StrategyService strategies,
                                 MarketDataClient marketData, NiftyOpeningBreakoutEngine engine, Clock clock) {
        this.configs = configs; this.positions = positions; this.strategies = strategies;
        this.marketData = marketData; this.engine = engine; this.clock = clock;
    }

    /** Today's Nifty 50 5-minute candles (cached for a minute) and the user's PAPER/LIVE strategies. */
    public Today today(Long userId) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(IST).toLocalDate();
        String index = Underlying.NIFTY.indexSymbol;
        List<Bar> bars = List.of();
        Double prevClose = null;
        try {
            var hit = barsCache;
            if (hit == null || Duration.between(hit.at(), now).compareTo(CACHE) >= 0) {
                PriceSeries five = marketData.getRange(index, Interval.MINUTE_5, today.minusDays(5).atStartOfDay(IST).toInstant(), now);
                List<Candle> done = five.candles().stream().filter(c -> c.timestamp().plus(Duration.ofMinutes(5)).compareTo(now) <= 0)
                        .sorted(Comparator.comparing(Candle::timestamp)).toList();
                Double pc = done.stream().filter(c -> c.timestamp().atZone(IST).toLocalDate().isBefore(today))
                        .reduce((a, b) -> b).map(c -> c.close().doubleValue()).orElse(null);
                List<Bar> todays = done.stream().filter(c -> c.timestamp().atZone(IST).toLocalDate().equals(today))
                        .map(c -> new Bar(c.timestamp(), c.open().doubleValue(), c.high().doubleValue(), c.low().doubleValue(), c.close().doubleValue())).toList();
                hit = new Chart(now, pc, todays);
                barsCache = hit;
            }
            bars = hit.bars(); prevClose = hit.previousClose();
        } catch (RuntimeException e) {
            log.warn("STATUS_CHART_FAILED: {}", e.getMessage());
        }
        Double spot = bars.isEmpty() ? prevClose : Double.valueOf(bars.get(bars.size() - 1).c());   // no unboxing of a null close
        return new Today(index, spot, prevClose, bars, forUser(userId));
    }

    private record Chart(Instant at, Double previousClose, List<Bar> bars) {}
    private volatile Chart barsCache;

    /** The user's strategies set to PAPER or LIVE, live ones first. */
    public List<Status> forUser(Long userId) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(IST).toLocalDate();
        List<LivePosition> todays = positions.findByTradeDateAndUserIdOrderByOpenedAtDesc(today, userId);
        List<Status> out = new ArrayList<>();
        for (StrategyLiveConfig cfg : configs.findByUserId(userId)) {
            if (cfg.getMode() == TradingMode.OFF) continue;
            Strategy s;
            try { s = strategies.load(cfg.getStrategyId()); } catch (RuntimeException e) { continue; }   // deleted
            out.add(status(s, cfg.getMode(), todays.stream()
                    .filter(p -> p.getStrategyId().equals(s.getId()) && p.getMode() == cfg.getMode()).toList(), now));
        }
        out.sort(Comparator.comparing((Status x) -> x.mode() != TradingMode.LIVE).thenComparing(Status::name));
        return out;
    }

    Status status(Strategy s, TradingMode mode, List<LivePosition> mine, Instant now) {
        Params p = new Params(new HashMap<String, Object>(s.getParameters() == null ? Map.of() : s.getParameters()));
        LocalTime from = time(p, "entryFrom", LocalTime.of(9, 20)), until = time(p, "entryUntil", LocalTime.of(14, 0));
        String u = Underlying.of(p.stringOr("underlying", "")).indexSymbol;
        double tp = p.doubleOr("targetPoints", 0);
        Engine e = engine(s, p, now);
        Optional<LivePosition> open = mine.stream().filter(x -> x.getStatus() == LivePosition.Status.OPEN).findFirst();
        if (open.isPresent()) {
            LivePosition x = open.get();
            return new Status(s.getId(), s.getName(), mode, u, State.IN_TRADE, "In " + x.getOptionType() + " from spot "
                    + Math.round(x.getEntrySpot()) + (x.getCurrentStop() == null ? "" : " · stop " + Math.round(x.getCurrentStop())),
                    e.spot(), List.of(), new Position(x.getOptionType(), x.getTradingsymbol(), x.getEntrySpot(), x.getCurrentStop(),
                    tp > 0 ? x.getEntrySpot() + ("CE".equals(x.getOptionType()) ? tp : -tp) : null,
                    x.getEntryPrice(), x.getQuantity(), x.getOpenedAt()), pnl(mine), from, until);
        }
        List<LivePosition> closed = mine.stream().filter(x -> x.getStatus() == LivePosition.Status.CLOSED).toList();
        if (!closed.isEmpty()) {
            LivePosition last = closed.get(0);
            return new Status(s.getId(), s.getName(), mode, u, State.DONE, readable(last.getExitReason()) + " · done for today",
                    e.spot(), List.of(), null, pnl(mine), from, until);
        }
        if (e.problem() != null)
            return new Status(s.getId(), s.getName(), mode, u, State.PROBLEM, e.problem(), e.spot(), List.of(), null, null, from, until);
        LocalTime t = now.atZone(IST).toLocalTime();
        boolean windowOpen = t.isBefore(until);
        String reason = e.dayReason();
        boolean notYet = e.dayStatus() == null || ("NO_TRADE".equals(e.dayStatus()) && reason != null && NOT_YET.matcher(reason).find());
        if ("SKIPPED".equals(e.dayStatus()) || ("NO_TRADE".equals(e.dayStatus()) && !notYet) || !windowOpen)
            return new Status(s.getId(), s.getName(), mode, u, State.NO_TRADE_TODAY,
                    !windowOpen && notYet ? "Entry window closed at " + until : "No trade today: " + reason, e.spot(), List.of(), null, null, from, until);
        String note = e.triggers().isEmpty()
                ? ("EMA_CROSS".equalsIgnoreCase(p.stringOr("referenceMode", "")) ? "Waiting for an EMA cross from " + from
                   : e.dayStatus() == null ? "Waiting for the opening candles" : "Waiting for a signal")
                : "Waiting for a signal";
        return new Status(s.getId(), s.getName(), mode, u, State.WAITING, note, e.spot(), e.triggers(), null, null, from, until);
    }

    private Engine engine(Strategy s, Params p, Instant now) {
        Engine hit = cache.get(s.getId());
        if (hit != null && Duration.between(hit.at(), now).compareTo(CACHE) < 0) return hit;
        Engine fresh;
        try {
            LocalDate today = now.atZone(IST).toLocalDate();
            NiftyOpeningBreakoutEngine.validateParams(p);
            Underlying u = Underlying.of(p.stringOr("underlying", ""));
            PriceSeries five = marketData.getRange(u.indexSymbol, Interval.MINUTE_5,
                    today.minusDays(Math.max(7, NiftyOpeningBreakoutEngine.warmupDays(p))).atStartOfDay(IST).toInstant(), now);
            PriceSeries one = marketData.getRange(u.indexSymbol, Interval.MINUTE_1, today.atStartOfDay(IST).toInstant(), now);
            NiftyOpeningBreakoutEngine.BreakoutResult r = engine.runLive(five, one, p, s.getId(), now, today);
            var day = r.days().stream().filter(d -> today.equals(d.date())).findFirst().orElse(null);
            List<Candle> bars = five.candles().stream().filter(c -> c.timestamp().plus(Duration.ofMinutes(5)).compareTo(now) <= 0)
                    .sorted(Comparator.comparing(Candle::timestamp)).toList();
            Double spot = bars.isEmpty() ? null : bars.get(bars.size() - 1).close().doubleValue();
            fresh = new Engine(now, spot, triggers(p, bars, today, spot), day == null ? null : day.status(), day == null ? null : day.reason(), null);
        } catch (IllegalArgumentException ex) {
            fresh = new Engine(now, null, List.of(), null, null, "Invalid parameters: " + ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("STATUS_FAILED strategy={}: {}", s.getId(), ex.getMessage());
            fresh = new Engine(now, null, List.of(), null, null, "Market data unavailable right now");
        }
        cache.put(s.getId(), fresh);
        return fresh;
    }

    /**
     * The index levels that would open a trade today, for the modes with fixed levels: previous-day range (CE above
     * yesterday's high, PE below its low), CPR (R1 / S1) and the opening range (reference candles' high / low, once
     * complete). Distance is the points the index still has to move (CE: level − spot; PE: spot − level).
     */
    static List<Trigger> triggers(Params p, List<Candle> bars, LocalDate today, Double spot) {
        String mode = p.stringOr("referenceMode", "").trim().toUpperCase(Locale.ROOT);
        String dirs = p.stringOr("directions", "BOTH").trim().toUpperCase(Locale.ROOT);
        boolean ce = !dirs.equals("PE"), pe = !dirs.equals("CE");
        LocalDate prevDay = bars.stream().map(c -> c.timestamp().atZone(IST).toLocalDate()).filter(d -> d.isBefore(today))
                .max(Comparator.naturalOrder()).orElse(null);
        List<Candle> prev = prevDay == null ? List.of() : bars.stream().filter(c -> c.timestamp().atZone(IST).toLocalDate().equals(prevDay)).toList();
        Double up = null, down = null;
        if (mode.equals("PREVIOUS_DAY") || mode.equals("CPR")) {
            if (prev.isEmpty()) return List.of();
            double h = prev.stream().mapToDouble(c -> c.high().doubleValue()).max().orElseThrow();
            double l = prev.stream().mapToDouble(c -> c.low().doubleValue()).min().orElseThrow();
            double cl = prev.get(prev.size() - 1).close().doubleValue();
            if (mode.equals("PREVIOUS_DAY")) { up = h; down = l; }
            else { double piv = (h + l + cl) / 3; up = 2 * piv - l; down = 2 * piv - h; }
        } else if (mode.isEmpty()) {   // opening range
            LocalTime ref = time(p, "referenceTime", LocalTime.of(9, 15));
            int minutes = p.intOr("referenceMinutes", 5);
            LocalTime end = ref.plusMinutes(minutes);
            List<Candle> range = bars.stream().filter(c -> {
                var z = c.timestamp().atZone(IST);
                return z.toLocalDate().equals(today) && !z.toLocalTime().isBefore(ref) && z.toLocalTime().isBefore(end);
            }).toList();
            if (range.size() * 5 < minutes) return List.of();   // still forming
            up = range.stream().mapToDouble(c -> c.high().doubleValue()).max().orElseThrow();
            down = range.stream().mapToDouble(c -> c.low().doubleValue()).min().orElseThrow();
        } else return List.of();   // EMA cross, gap fill: no fixed level
        double s = spot == null ? Double.NaN : spot;
        List<Trigger> out = new ArrayList<>();
        if (ce) out.add(new Trigger("CE", round(up), round(up - s)));
        if (pe) out.add(new Trigger("PE", round(down), round(s - down)));
        return out;
    }

    private static double round(double v) { return Double.isNaN(v) ? v : Math.round(v * 100) / 100.0; }

    private static Double pnl(List<LivePosition> ps) {
        double sum = 0; boolean any = false;
        for (LivePosition x : ps) if (x.getPnl() != null) { sum += x.getPnl(); any = true; }
        return any ? Math.round(sum * 100) / 100.0 : null;
    }

    static String readable(String exitReason) {
        if (exitReason == null) return "Closed";
        return switch (exitReason) {
            case "TARGET_HIT" -> "Target hit";
            case "SL_HIT" -> "Stop hit";
            case "EOD_SQUAREOFF" -> "Squared off at 15:20";
            case "MAX_DAY_LOSS" -> "Closed at your max day loss";
            case "KILL_SWITCH" -> "Closed by the kill switch";
            case "STOPPED_BY_USER" -> "Closed by you";
            default -> exitReason.replace('_', ' ').toLowerCase(Locale.ROOT);
        };
    }

    private static LocalTime time(Params p, String name, LocalTime fallback) {
        String v = p.stringOr(name, "").trim();
        try { return v.isEmpty() ? fallback : LocalTime.parse(v); } catch (DateTimeException e) { return fallback; }
    }
}
