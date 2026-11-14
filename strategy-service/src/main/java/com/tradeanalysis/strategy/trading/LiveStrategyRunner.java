package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.strategy.engine.CandleMemo;

import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.common.market.Underlying;
import com.tradeanalysis.strategy.engine.MarketDataClient;
import com.tradeanalysis.strategy.engine.NiftyOpeningBreakoutEngine;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.model.Strategy;
import com.tradeanalysis.strategy.service.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/**
 * Trades strategies switched to PAPER or LIVE, separately for every user who switched them on (each user has their
 * own positions and orders; the engine runs once per strategy). A few seconds after every minute's close in market hours
 * (trading.runner.cron, 3 s past by default, just after market-data counts the closed candle as final, so 5-minute signals go out right after their candle closes) it reruns the backtest engine
 * (NIFTY_OPENING_BREAKOUT, same parameters) on today's completed candles and makes positions follow it:
 * a new engine entry opens a position (BUY the option), an engine exit closes it (SELL). Because the engine is the
 * one the backtests use, live decisions follow exactly the backtested rules, decided on completed candles.
 *
 * An entry is only taken while fresh (signal candle closed at most trading.runner.max-entry-delay-minutes ago),
 * so a late start never enters an old trade.
 *
 * Between runs, {@link LiveExitWatcher} closes a position the moment the index touches its stop or target, through
 * {@link #exitOnTouch}. Every change to positions happens under one lock, so the two never sell the same position twice.
 */
@Component
@ConditionalOnProperty(name = "trading.runner.enabled", havingValue = "true", matchIfMissing = true)
public class LiveStrategyRunner {

    private static final Logger log = LoggerFactory.getLogger(LiveStrategyRunner.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final StrategyLiveConfigRepository configs;
    private final LivePositionRepository positions;
    private final TradeOrderRepository orders;
    private final TradingControlRepository controls;
    private final OrderService orderService;
    private final StrategyService strategies;
    private final MarketDataClient marketData;
    private final NiftyOpeningBreakoutEngine engine;
    private final NiftyOptions options;
    private final KiteApi kite;
    private final Clock clock;
    private final Duration maxEntryDelay;
    private final TradeAlerts alerts;
    private final Set<String> invalidWarned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Held while positions are opened or closed: the minute run and the exit watcher never act on one at the same time. */
    private final Object positionLock = new Object();

    public LiveStrategyRunner(StrategyLiveConfigRepository configs, LivePositionRepository positions, TradeOrderRepository orders,
                              TradingControlRepository controls, OrderService orderService, StrategyService strategies,
                              MarketDataClient marketData, NiftyOpeningBreakoutEngine engine, NiftyOptions options, KiteApi kite,
                              Clock clock, @Value("${trading.runner.max-entry-delay-minutes:5}") long maxEntryDelayMinutes,
                              TradeAlerts alerts) {
        this.alerts = alerts;
        this.configs = configs; this.positions = positions; this.orders = orders; this.controls = controls;
        this.orderService = orderService; this.strategies = strategies; this.marketData = marketData; this.engine = engine;
        this.options = options; this.kite = kite; this.clock = clock;
        this.maxEntryDelay = Duration.ofMinutes(maxEntryDelayMinutes);
    }

    @Scheduled(cron = "${trading.runner.cron:3 * * * * *}", zone = "Asia/Kolkata")
    public void tick() {
        try { orderService.syncLiveOrders(); } catch (RuntimeException e) { log.warn("LIVE_SYNC_FAILED {}", e.getMessage()); }
        refreshLivePrices();
        if (!marketOpen(clock.instant())) return;
        for (String line : runAll())   // log only what changed (an entry, exit, refusal or error), not every quiet minute
            if (!line.endsWith("no change")) log.info("RUNNER {}", line);
    }

    /** Runs every PAPER/LIVE strategy once now (engine once per strategy, positions per user); a line per user and strategy. */
    public List<String> runAll() {
        long started = System.nanoTime();
        // Every strategy reads the same index candles: fetch them once per run, not twice per strategy (with 18
        // strategies that took most of the minute, so the last ones acted on a closed candle ~50 s late).
        CandleMemo candles = new CandleMemo(clock.instant());
        List<String> report = candles.with(this::runEach);
        log.info("RUNNER_TIMING lines={} candleFetches={} total={}ms", report.size(), candles.fetches(),
                (System.nanoTime() - started) / 1_000_000);
        return report;
    }

    private List<String> runEach() {
        List<String> report = new ArrayList<>();
        Map<Long, List<StrategyLiveConfig>> byStrategy = new TreeMap<>();
        for (StrategyLiveConfig cfg : configs.findByModeNot(TradingMode.OFF))
            byStrategy.computeIfAbsent(cfg.getStrategyId(), k -> new ArrayList<>()).add(cfg);
        for (var e : byStrategy.entrySet()) {
            Evaluation ev;
            try {
                ev = evaluate(e.getKey());
            } catch (RuntimeException ex) {
                log.error("RUNNER_FAILED strategy={}: {}", e.getKey(), ex.getMessage(), ex);
                report.add("#" + e.getKey() + ": error — " + ex.getMessage());
                continue;
            }
            for (StrategyLiveConfig cfg : e.getValue()) {
                try {
                    synchronized (positionLock) {
                        report.add(ev.result == null ? ev.problem : reconcile(cfg, ev.strategy, ev.params, ev.result, ev.today, ev.now));
                    }
                } catch (RuntimeException ex) {
                    log.error("RUNNER_FAILED strategy={} user={} mode={}: {}", cfg.getStrategyId(), cfg.getUserId(), cfg.getMode(), ex.getMessage(), ex);
                    report.add("#" + cfg.getStrategyId() + " user " + cfg.getUserId() + ": error — " + ex.getMessage());
                }
            }
        }
        synchronized (positionLock) { report.addAll(enforceDayLoss()); }
        return report;
    }

    /**
     * Closes an open position now because the index touched its stop or target (from {@link LiveExitWatcher}). Reads the
     * position again under the lock, so one the minute run already closed is left alone. Returns what it did, or null.
     */
    public String exitOnTouch(Long positionId, double spot, String reason) {
        synchronized (positionLock) {
            LivePosition p = positions.findById(positionId).orElse(null);
            if (p == null || p.getStatus() != LivePosition.Status.OPEN) return null;
            Strategy s = strategies.load(p.getStrategyId());
            Params params = new Params(new HashMap<>(s.getParameters() == null ? Map.of() : s.getParameters()));
            return "#" + p.getStrategyId() + " " + p.getMode() + " user " + p.getUserId() + ": " + exit(p, spot, reason, null, "STRATEGY", params);
        }
    }

    /**
     * Each user's own max day loss covers all their strategies together: once today's loss in a mode (realized plus
     * open positions) reaches it, every open strategy position of that user in that mode is closed. New entries are
     * refused for the rest of the day (see OrderService#dayLossRefusal).
     */
    public List<String> enforceDayLoss() {
        List<String> report = new ArrayList<>();
        LocalDate today = LocalDate.now(clock.withZone(IST));
        Map<String, List<LivePosition>> byUserMode = new TreeMap<>();
        for (TradingMode mode : List.of(TradingMode.PAPER, TradingMode.LIVE))
            for (LivePosition p : positions.findByTradeDateAndModeAndStatus(today, mode, LivePosition.Status.OPEN))
                if (p.getUserId() != null) byUserMode.computeIfAbsent(p.getUserId() + "|" + mode, k -> new ArrayList<>()).add(p);
        for (List<LivePosition> open : byUserMode.values()) {
            LivePosition first = open.get(0);
            String refusal;
            try { refusal = orderService.dayLossRefusal(first.getMode(), first.getUserId()); }
            catch (RuntimeException e) { log.warn("DAY_LOSS_CHECK_FAILED user={}: {}", first.getUserId(), e.getMessage()); continue; }
            if (refusal == null) continue;
            for (LivePosition p : open) {
                try {
                    Params params = new Params(new HashMap<>(strategies.load(p.getStrategyId()).getParameters()));
                    report.add("#" + p.getStrategyId() + " " + p.getMode() + " user " + p.getUserId() + ": "
                            + exit(p, null, "MAX_DAY_LOSS", null, "MAX_DAY_LOSS", params));
                } catch (RuntimeException e) {
                    log.error("DAY_LOSS_EXIT_FAILED position={}: {}", p.getId(), e.getMessage(), e);
                    report.add("#" + p.getStrategyId() + " user " + p.getUserId() + ": max day loss exit failed — " + e.getMessage());
                }
            }
            log.warn("MAX_DAY_LOSS user={} mode={}: {}", first.getUserId(), first.getMode(), refusal);
        }
        return report;
    }

    private record Evaluation(Strategy strategy, Params params, NiftyOpeningBreakoutEngine.BreakoutResult result,
                              LocalDate today, Instant now, String problem) {}

    static boolean marketOpen(Instant now) {
        ZonedDateTime t = now.atZone(IST);
        DayOfWeek d = t.getDayOfWeek();
        LocalTime time = t.toLocalTime();
        return (d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY
                && !time.isBefore(LocalTime.of(9, 20)) && !time.isAfter(LocalTime.of(15, 31)))
                || com.tradeanalysis.common.market.SpecialSessions.open(now);   // e.g. Muhurat trading on a Sunday
    }

    String run(StrategyLiveConfig cfg) {
        Evaluation ev = evaluate(cfg.getStrategyId());
        return ev.result == null ? ev.problem : reconcile(cfg, ev.strategy, ev.params, ev.result, ev.today, ev.now);
    }

    /** Runs the backtest engine on today's completed candles (the same for every user trading this strategy). */
    private Evaluation evaluate(Long strategyId) {
        Strategy s = strategies.load(strategyId);
        Instant now = clock.instant();
        LocalDate today = now.atZone(IST).toLocalDate();
        if (!"NIFTY_OPENING_BREAKOUT".equals(s.getType()))
            return new Evaluation(s, null, null, today, now, "#" + s.getId() + ": only NIFTY_OPENING_BREAKOUT can trade live");
        Params params = new Params(new HashMap<>(s.getParameters()));
        String symbol = underlying(params).indexSymbol;   // NIFTY 50 or NIFTY BANK, as the strategy says
        PriceSeries five = CandleMemo.getRange(marketData, symbol, Interval.MINUTE_5,
                today.minusDays(NiftyOpeningBreakoutEngine.warmupDays(params)).atStartOfDay(IST).toInstant(), now);
        PriceSeries one = CandleMemo.getRange(marketData, symbol, Interval.MINUTE_1, today.atStartOfDay(IST).toInstant(), now);
        try {
            NiftyOpeningBreakoutEngine.validateParams(params);
        } catch (IllegalArgumentException e) {   // saved before parameters were checked: skip it, warn once a day
            String problem = "#" + s.getId() + ": not traded, invalid parameters (" + e.getMessage() + "); fix or recreate the strategy";
            if (invalidWarned.add(s.getId() + "|" + today + "|" + e.getMessage())) log.warn("RUNNER_SKIPPED {}", problem);
            return new Evaluation(s, null, null, today, now, problem);
        }
        return new Evaluation(s, params, engine.runLive(five, one, params, s.getId(), now, today), today, now, null);
    }

    /** Makes today's positions follow the engine's trades. */
    String reconcile(StrategyLiveConfig cfg, Strategy s, Params params, NiftyOpeningBreakoutEngine.BreakoutResult r,
                     LocalDate today, Instant now) {
        List<String> did = new ArrayList<>();
        Set<NiftyTradeLog> open = Collections.newSetFromMap(new IdentityHashMap<>());
        open.addAll(r.openTrades());
        List<NiftyTradeLog> todays = new ArrayList<>(r.openTrades());
        r.tradeLogs().stream().filter(t -> today.equals(t.getTradeDate())).forEach(todays::add);
        for (NiftyTradeLog t : todays) {
            Optional<LivePosition> existing = positions.findByStrategyIdAndUserIdAndModeAndEntryCandle(s.getId(), cfg.getUserId(), cfg.getMode(), t.getEntryTimestamp());
            boolean stillOpen = open.contains(t);
            if (existing.isEmpty()) {
                if (!stillOpen) continue;                                   // engine trade already over before we saw it
                Instant signalAt = t.getEntryTimestamp().plus(Duration.ofMinutes(5));
                if (now.isAfter(signalAt.plus(maxEntryDelay))) {
                    did.add("skipped stale entry from " + signalAt.atZone(IST).toLocalTime());
                    continue;
                }
                if (controls.get().isKillSwitch()) { did.add("entry blocked: kill switch on"); continue; }
                if (cfg.getMode() == TradingMode.LIVE && !controls.get().isLiveEnabled()) { did.add("entry blocked: live trading is off"); continue; }
                if (cfg.getMode() == TradingMode.LIVE && orderService.accessRefusal(cfg.getUserId()) != null) { did.add("entry blocked: no live access"); continue; }
                String dayLoss = orderService.dayLossRefusal(cfg.getMode(), cfg.getUserId());
                if (dayLoss != null) { did.add("entry blocked: " + dayLoss); continue; }
                did.add(enter(cfg, s, params, t, today));
            } else if (existing.get().getStatus() == LivePosition.Status.OPEN) {
                LivePosition p = existing.get();
                if (stillOpen) {
                    p.trackStop(t.getFinalStopLoss());
                    positions.save(p);
                } else {
                    did.add(exit(p, t.getExitSpotPrice(), t.getExitReason(), estimate(t), "STRATEGY", params));
                }
            }
        }
        return "#" + s.getId() + " " + cfg.getMode() + " user " + cfg.getUserId() + ": " + (did.isEmpty() ? "no change" : String.join("; ", did));
    }

    private String enter(StrategyLiveConfig cfg, Strategy s, Params params, NiftyTradeLog t, LocalDate today) {
        KiteApi.OptionInstrument opt;
        try {
            opt = options.resolve(underlying(params), t.getOptionType(), t.getStrikePrice(), today);
        } catch (RuntimeException e) {
            LivePosition failed = new LivePosition(s.getId(), cfg.getUserId(), today, cfg.getMode(), t.getOptionType(), null, 0,
                    t.getEntryTimestamp(), t.getEntrySpotPrice(), t.getFinalStopLoss());
            failed.failed("Option not found: " + e.getMessage());
            positions.save(failed);
            return "entry failed: " + e.getMessage();
        }
        int qty = params.intOr("lots", 2) * opt.lotSize();
        OrderRequest req = marketable(opt.tradingsymbol(), "BUY", qty, cfg.getProduct(), opt.tickSize(), cfg.getMode());
        LivePosition p = new LivePosition(s.getId(), cfg.getUserId(), today, cfg.getMode(), t.getOptionType(), opt.tradingsymbol(), qty,
                t.getEntryTimestamp(), t.getEntrySpotPrice(), t.getFinalStopLoss());
        if (req == null) {
            p.failed("No option price from Kite for a live order");
            positions.save(p);
            return "entry failed: no option price";
        }
        TradeOrder o = orderService.place(req, cfg.getMode(), "STRATEGY", s.getId(), cfg.getUserId(), false);
        p.entered(o);
        positions.save(p);
        return o.getStatus() == TradeOrder.Status.REJECTED
                ? "entry refused: " + o.getMessage()
                : "BUY " + qty + " " + opt.tradingsymbol() + (o.getAveragePrice() == null ? "" : " @ " + o.getAveragePrice());
    }

    /** Closes a position: cancels an unfilled live entry, otherwise sells what was bought. */
    String exit(LivePosition p, Double exitSpot, String reason, Double estimate, String source, Params params) {
        TradeOrder entry = p.getEntryOrderId() == null ? null : orders.findById(p.getEntryOrderId()).orElse(null);
        if (entry != null && entry.getStatus() == TradeOrder.Status.OPEN && entry.getFilledQuantity() == 0) {
            try { orderService.cancel(entry.getId()); } catch (RuntimeException e) { log.warn("Cancel entry {} failed: {}", entry.getId(), e.getMessage()); }
            p.closed(null, exitSpot, "ENTRY_NOT_FILLED", 0.0, "Entry was not filled; cancelled");
            positions.save(p);
            alerts.positionClosed(p);
            return "entry cancelled (not filled)";
        }
        int qty = entry != null && entry.getFilledQuantity() > 0 ? entry.getFilledQuantity() : p.getQuantity();
        double tick = options.find(underlying(params), p.getTradingsymbol()).map(KiteApi.OptionInstrument::tickSize).orElse(0.05);
        OrderRequest req = marketable(p.getTradingsymbol(), "SELL", qty, entry == null ? "MIS" : entry.getProduct(), tick, p.getMode());
        if (req == null) req = new OrderRequest("NFO", p.getTradingsymbol(), "SELL", qty, entry == null ? "MIS" : entry.getProduct(), "MARKET", null, null);
        TradeOrder o = orderService.place(req, p.getMode(), source, p.getStrategyId(), p.getUserId(), true);
        if (entry != null) p.entryPrice(entry.getAveragePrice());
        p.closed(o, exitSpot, reason, pnl(p, o, estimate, params), o.getMessage());
        positions.save(p);
        alerts.positionClosed(p);
        return "SELL " + qty + " " + p.getTradingsymbol() + " (" + reason + ")";
    }

    /** Real option P&L when both fills have prices, otherwise the engine's estimate. */
    private Double pnl(LivePosition p, TradeOrder exit, Double estimate, Params params) {
        Double in = p.getEntryPrice(), out = exit == null ? null : exit.getAveragePrice();
        if (in == null || out == null) return estimate;
        return (out - in) * (exit.getFilledQuantity() > 0 ? exit.getFilledQuantity() : p.getQuantity())
                - (params == null ? 0 : params.doubleOr("costPerTrade", 0));
    }

    /** The index a strategy trades (NIFTY unless its parameters say BANKNIFTY); NIFTY when there are no parameters. */
    private static Underlying underlying(Params params) {
        return params == null ? Underlying.NIFTY : NiftyOpeningBreakoutEngine.underlyingOf(params);
    }

    private static Double estimate(NiftyTradeLog t) { return t.getEstimatedPnl(); }

    /** Live: LIMIT a little through the last price (3%) so it fills like a market order with a cap; paper: MARKET. */
    private OrderRequest marketable(String symbol, String side, int qty, String product, double tick, TradingMode mode) {
        if (mode == TradingMode.PAPER) return new OrderRequest("NFO", symbol, side, qty, product, "MARKET", null, null);
        Double ltp;
        try { ltp = kite.ltp(List.of("NFO:" + symbol)).get("NFO:" + symbol); } catch (RuntimeException e) { ltp = null; }
        if (ltp == null || ltp <= 0) return null;
        double raw = "BUY".equals(side) ? ltp * 1.03 : ltp * 0.97;
        BigDecimal ticks = BigDecimal.valueOf(raw / tick).setScale(0, "BUY".equals(side) ? RoundingMode.CEILING : RoundingMode.FLOOR);
        double price = Math.max(tick, ticks.multiply(BigDecimal.valueOf(tick)).setScale(2, RoundingMode.HALF_UP).doubleValue());
        return new OrderRequest("NFO", symbol, side, qty, product, "LIMIT", price, null);
    }

    /** Live fills arrive after the order is sent: fill in entry/exit prices and real P&L once known. */
    private void refreshLivePrices() {
        for (LivePosition p : positions.findByTradeDateOrderByOpenedAtDesc(LocalDate.now(clock.withZone(IST)))) {
            if (p.getMode() != TradingMode.LIVE) continue;
            TradeOrder entry = p.getEntryOrderId() == null ? null : orders.findById(p.getEntryOrderId()).orElse(null);
            if (entry != null && p.getEntryPrice() == null) p.entryPrice(entry.getAveragePrice());
            if (p.getStatus() == LivePosition.Status.CLOSED && p.getExitOrderId() != null && p.getExitPrice() == null) {
                orders.findById(p.getExitOrderId()).filter(o -> o.getAveragePrice() != null)
                        .ifPresent(o -> p.closed(o, p.getExitSpot(), p.getExitReason(), pnl(p, o, p.getPnl(), null), o.getMessage()));
            }
            positions.save(p);
        }
    }
}
