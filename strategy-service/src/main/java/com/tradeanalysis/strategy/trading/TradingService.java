package com.tradeanalysis.strategy.trading;

import com.tradeanalysis.strategy.model.Strategy;
import com.tradeanalysis.strategy.service.StrategyService;
import com.tradeanalysis.strategy.user.AppUserRepository;
import com.tradeanalysis.strategy.user.CurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/** Strategy live modes, global controls and the kill switch. */
@Service
public class TradingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TradingService.class);

    private final StrategyLiveConfigRepository configs;
    private final TradingControlRepository controls;
    private final TradeOrderRepository orders;
    private final LivePositionRepository positions;
    private final OrderService orderService;
    private final StrategyService strategies;
    private final AppUserRepository users;
    private final ObjectProvider<LiveStrategyRunner> runner;
    private final BrokerAccounts accounts;
    private final com.tradeanalysis.strategy.repository.StrategyRepository strategyRepo;
    private final LiveOrderBilling billing;
    private final com.tradeanalysis.strategy.compliance.Compliance compliance;
    private final com.tradeanalysis.strategy.compliance.ComplianceAudit audit;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    /** Audit action for every strategy mode change, whichever way it was made. */
    public static final String MODE_ACTION = "STRATEGY_MODE";

    public TradingService(StrategyLiveConfigRepository configs, TradingControlRepository controls, TradeOrderRepository orders,
                          LivePositionRepository positions, OrderService orderService, StrategyService strategies,
                          AppUserRepository users, ObjectProvider<LiveStrategyRunner> runner, BrokerAccounts accounts,
                          com.tradeanalysis.strategy.repository.StrategyRepository strategyRepo, LiveOrderBilling billing,
                          com.tradeanalysis.strategy.compliance.Compliance compliance,
                          com.tradeanalysis.strategy.compliance.ComplianceAudit audit,
                          org.springframework.transaction.PlatformTransactionManager txManager) {
        this.audit = audit; this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.configs = configs; this.controls = controls; this.orders = orders; this.positions = positions;
        this.orderService = orderService; this.strategies = strategies; this.users = users; this.runner = runner;
        this.accounts = accounts; this.strategyRepo = strategyRepo; this.billing = billing; this.compliance = compliance;
    }

    public record StrategyMode(Long strategyId, String name, String type, String direction, TradingMode mode, String product) {}

    /**
     * Saves one strategy mode change together with its audit record, in one transaction: if the record can't be written
     * the change is not kept either, so no mode (above all LIVE) is ever switched without a trace. The record names the
     * strategy, mode and product, whose setting it is when someone else changed it (kill switch), and {@code via}.
     */
    private void saveMode(StrategyLiveConfig c, TradingMode mode, String product, String via, boolean nameUser) {
        Long actor = CurrentUser.id().orElse(null);
        tx.executeWithoutResult(status -> {
            c.set(mode, product);
            configs.save(c);
            audit.recordOrFail(actor, MODE_ACTION, "strategy " + c.getStrategyId() + " " + c.getMode() + " " + c.getProduct()
                    + (nameUser ? " user " + c.getUserId() : "") + (via == null ? "" : " (" + via + ")"));
        });
    }

    /** The caller's own mode for every strategy they can see. */
    public List<StrategyMode> strategyModes() {
        Map<Long, StrategyLiveConfig> byId = new HashMap<>();
        CurrentUser.id().ifPresent(me -> configs.findByUserId(me).forEach(c -> byId.put(c.getStrategyId(), c)));
        List<StrategyMode> out = new ArrayList<>();
        for (Strategy s : strategies.list()) {
            StrategyLiveConfig c = byId.get(s.getId());
            out.add(new StrategyMode(s.getId(), s.getName(), s.getType(), s.getParameters().get("directions"),
                    c == null ? TradingMode.OFF : c.getMode(), c == null ? "MIS" : c.getProduct()));
        }
        out.sort(Comparator.comparing(StrategyMode::strategyId).reversed());
        return out;
    }

    /** Sets the caller's own mode: OFF/PAPER for GOLD+ users; LIVE needs PREMIUM + their own Kite or Angel One account (or ADMIN) and live trading enabled. */
    public StrategyMode setMode(Long strategyId, TradingMode mode, String product) {
        return setMode(strategyId, mode, product, null);
    }

    /** {@link #setMode(Long, TradingMode, String)}, recording {@code via} (why, e.g. "portfolio 12") with the change. */
    public StrategyMode setMode(Long strategyId, TradingMode mode, String product, String via) {
        Strategy s = strategies.get(strategyId);
        if (mode != TradingMode.OFF && CurrentUser.limitedPlan().map(p -> !p.paperTrading()).orElse(false))
            throw new TradingException(HttpStatus.FORBIDDEN, "Your plan is " + CurrentUser.limitedPlan().get()
                    + ". Upgrade to GOLD for paper trading (you can always turn trading off).");
        if (mode != TradingMode.OFF && !"NIFTY_OPENING_BREAKOUT".equals(s.getType()))
            throw new TradingException("Only NIFTY_OPENING_BREAKOUT strategies can trade live");
        if (product != null && !Set.of("MIS", "NRML").contains(product)) throw new TradingException("Product must be MIS or NRML");
        if (mode == TradingMode.LIVE) {
            requireLive();
            if (!orderService.liveEnabled()) throw new TradingException(OrderService.LIVE_OFF);
        }
        if (mode == TradingMode.LIVE && !CurrentUser.admin()) checkLivePlan(s, strategyId);
        if (mode != TradingMode.OFF && !CurrentUser.admin() && currentMaxDayLoss() == null)
            throw new TradingException("Set your max day loss first (Trading → Max day loss): it caps your loss for a day "
                    + "across all your strategies together");
        Long me = CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first"));
        StrategyLiveConfig c = configs.findByStrategyIdAndUserId(strategyId, me).orElseGet(() -> new StrategyLiveConfig(strategyId, me));
        saveMode(c, mode, product, via, false);
        return strategyModes().stream().filter(m -> m.strategyId().equals(strategyId)).findFirst().orElseThrow();
    }

    /**
     * Sets the caller's mode for several strategies at once (e.g. every strategy of a portfolio). The plan and the
     * live checks apply to all; a strategy that can't take the mode is reported and skipped.
     */
    public record BulkResult(List<StrategyMode> changed, List<Map<String, Object>> skipped) {}

    /** @param via why the strategies change together, recorded with each change (e.g. "portfolio 12") */
    public BulkResult setModes(Collection<Long> strategyIds, TradingMode mode, String product, String via) {
        if (mode == null) throw new TradingException("Mode must be OFF, PAPER or LIVE");
        List<StrategyMode> changed = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(strategyIds)) {
            try {
                changed.add(setMode(id, mode, product, via));
            } catch (TradingException e) {
                // Plan, live-permission and day-loss refusals hold for every strategy: report them once.
                String reason = String.valueOf(e.getReason());
                if (e.getStatusCode().value() == 403 || e.getStatusCode().value() == 402 || reason.startsWith("Set your max day loss")
                        || reason.startsWith(OrderService.LIVE_OFF)) throw e;
                skipped.add(Map.of("strategyId", id, "reason", reason));
            } catch (RuntimeException e) {
                skipped.add(Map.of("strategyId", id, "reason", String.valueOf(e.getMessage())));
            }
        }
        return new BulkResult(changed, skipped);
    }

    /**
     * The caller's plan limits for switching a strategy to LIVE: its lots within the plan's lots per order, and room for
     * one more live strategy. A 400 (not 403), so "Trade all" reports the strategies that don't fit and sets the rest.
     */
    private void checkLivePlan(Strategy s, Long strategyId) {
        com.tradeanalysis.strategy.user.Plan plan = CurrentUser.limitedPlan().orElse(null);
        if (plan == null) return;
        int lots = new com.tradeanalysis.common.indicator.Params(s.getParameters() == null ? new HashMap<String, Object>() : new HashMap<String, Object>(s.getParameters())).intOr("lots", 2);
        if (lots > plan.maxLiveLots())
            throw new TradingException("Your " + plan.label() + " plan trades live up to " + plan.maxLiveLots() + " lots per order and this strategy uses "
                    + lots + ": lower its lots or upgrade");
        Long me = CurrentUser.id().orElse(null);
        String credit = billing.refusal(me);
        if (credit != null) throw new TradingException(HttpStatus.PAYMENT_REQUIRED, credit);
        long live = configs.findByUserId(me).stream()
                .filter(c -> c.getMode() == TradingMode.LIVE && !c.getStrategyId().equals(strategyId)).count();
        if (live >= plan.maxLiveStrategies())
            throw new TradingException("Your " + plan.label() + " plan allows " + plan.maxLiveStrategies() + " strategies trading live at once: "
                    + "switch one off or upgrade");
    }

    /** The caller's own max day loss (null = not set). */
    public Double currentMaxDayLoss() {
        return CurrentUser.id().flatMap(users::findById).map(com.tradeanalysis.strategy.user.AppUser::getMaxDayLoss).orElse(null);
    }

    /** The caller's own pair stop in points (0 = close the partner at once), or null when off. */
    public Integer currentPairStopPoints() {
        return CurrentUser.id().flatMap(users::findById).map(com.tradeanalysis.strategy.user.AppUser::getPairStopPoints).orElse(null);
    }

    public static final int MAX_PAIR_STOP_POINTS = 500;

    /**
     * Sets the caller's pair stop: null turns it off; 0 closes the partner trade at once when one of two strategies
     * holding the same option from the same minute is stopped out at a loss; more waits that many index points.
     */
    @org.springframework.transaction.annotation.Transactional
    public Integer setPairStopPoints(Integer points) {
        Long me = CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first"));
        if (points != null && (points < 0 || points > MAX_PAIR_STOP_POINTS))
            throw new TradingException("Pair stop must be between 0 and " + MAX_PAIR_STOP_POINTS + " points, or off");
        var u = users.findById(me).orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first"));
        u.setPairStopPoints(points);
        users.save(u);
        return u.getPairStopPoints();
    }

    public static final double MAX_DAY_LOSS_CAP = 100_000_000;

    /** Sets the caller's own max day loss: a positive amount in rupees, for all their strategies together. */
    @org.springframework.transaction.annotation.Transactional
    public Double setMaxDayLoss(Double amount) {
        Long me = CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first"));
        if (amount == null || amount.isNaN() || amount <= 0 || amount > MAX_DAY_LOSS_CAP)
            throw new TradingException("Max day loss must be a positive amount in rupees (up to " + (long) MAX_DAY_LOSS_CAP + ")");
        var u = users.findById(me).orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first"));
        u.setMaxDayLoss((double) Math.round(amount));
        users.save(u);
        return u.getMaxDayLoss();
    }

    public TradingControl controls() { return controls.get(); }

    public TradingControl updateControls(Integer maxOrdersPerDay, Integer maxQuantityPerOrder, Double dailyLossLimit) {
        requireAdmin("change trading limits");
        if (maxOrdersPerDay != null && maxOrdersPerDay < 0 || maxQuantityPerOrder != null && maxQuantityPerOrder < 0
                || dailyLossLimit != null && dailyLossLimit < 0) throw new TradingException("Limits cannot be negative");
        TradingControl c = controls.get();
        c.update(null, maxOrdersPerDay, maxQuantityPerOrder, dailyLossLimit, CurrentUser.id().orElse(null));
        return controls.save(c);
    }

    public record KillResult(int cancelledOrders, int closedPositions, int strategiesStopped, List<String> details) {}

    /**
     * Stops everything: kill switch on, every strategy OFF, open live orders cancelled, open strategy positions
     * closed, for every user. ADMIN only.
     */
    public KillResult engageKillSwitch() {
        TradingControl c = controls.get();
        c.update(true, null, null, null, CurrentUser.id().orElse(null));
        controls.save(c);
        int stopped = 0;
        for (StrategyLiveConfig cfg : configs.findByModeNot(TradingMode.OFF)) {
            saveMode(cfg, TradingMode.OFF, null, "kill switch", true);
            stopped++;
        }
        List<String> details = new ArrayList<>();
        int cancelled = 0;
        for (TradeOrder o : orders.findByModeAndStatus(TradingMode.LIVE, TradeOrder.Status.OPEN)) {
            try { orderService.cancel(o.getId()); cancelled++; } catch (RuntimeException e) { details.add("order " + o.getId() + ": " + e.getMessage()); }
        }
        int closed = 0;
        LiveStrategyRunner r = runner.getIfAvailable();
        for (LivePosition p : positions.findByStatus(LivePosition.Status.OPEN)) {
            if (r == null) { details.add("position " + p.getId() + ": runner disabled, close it manually"); continue; }
            try { details.add(r.exit(p, null, "KILL_SWITCH", null, "KILL_SWITCH", null)); closed++; }
            catch (RuntimeException e) { details.add("position " + p.getId() + ": " + e.getMessage()); }
        }
        return new KillResult(cancelled, closed, stopped, details);
    }

    /**
     * Turns live trading on or off for the whole platform (ADMIN). Off: no new live orders or live strategy entries for
     * anyone; open live positions are still managed and closed as usual. LIVE strategies keep their mode and resume
     * entries when it is turned on again.
     */
    public TradingControl setLiveTrading(boolean enabled) {
        requireAdmin("turn live trading " + (enabled ? "on" : "off"));
        TradingControl c = controls.get();
        c.setLiveEnabled(enabled, CurrentUser.id().orElse(null));
        log.warn("LIVE_TRADING_{} by user {}", enabled ? "ON" : "OFF", CurrentUser.id().orElse(null));
        return controls.save(c);
    }

    /** Who may trade live while live trading is restricted, and what everyone else sees (ADMIN). */
    public record LiveAccess(boolean restricted, String message, List<Long> allowedUserIds) {}

    public LiveAccess liveAccess() {
        requireAdmin("see live access");
        TradingControl c = controls.get();
        return new LiveAccess(c.isLiveRestricted(), c.getLiveRestrictedMessage(),
                users.findAll().stream().filter(com.tradeanalysis.strategy.user.AppUser::isLiveAccess)
                        .map(com.tradeanalysis.strategy.user.AppUser::getId).sorted().toList());
    }

    /** Turns the restriction on or off and/or sets the message users without live access see (ADMIN; blank = default). */
    public LiveAccess setLiveRestriction(Boolean restricted, String message) {
        requireAdmin("restrict live trading");
        if (message != null && message.strip().length() > 300) throw new TradingException("Keep the message under 300 characters");
        TradingControl c = controls.get();
        c.setLiveRestriction(restricted, message, CurrentUser.id().orElse(null));
        controls.save(c);
        log.warn("LIVE_RESTRICTION restricted={} by user {}", c.isLiveRestricted(), CurrentUser.id().orElse(null));
        return liveAccess();
    }

    /**
     * Gives or takes a user's live access (ADMIN). Taking it stops new live orders and entries for them while live
     * trading is restricted; their open live positions are still managed and closed.
     */
    @org.springframework.transaction.annotation.Transactional
    public LiveAccess setUserLiveAccess(Long userId, boolean allowed) {
        requireAdmin("change live access");
        com.tradeanalysis.strategy.user.AppUser u = users.findById(userId)
                .orElseThrow(() -> new TradingException(HttpStatus.NOT_FOUND, "No user " + userId));
        u.setLiveAccess(allowed);
        users.save(u);
        log.warn("LIVE_ACCESS user {} {} by user {}", userId, allowed ? "ALLOWED" : "REMOVED", CurrentUser.id().orElse(null));
        return liveAccess();
    }

    public record StopResult(int strategiesStopped, int positionsClosed, List<String> details) {}

    /**
     * The caller's own emergency stop: every one of their strategies OFF and their open strategy positions closed now
     * (paper and live). Other users are not touched; the admin kill switch is separate.
     */
    public StopResult stopMine() {
        Long me = CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first"));
        StopResult r = stop(me, null, "stop all");
        log.warn("STOP_MINE user={} strategies={} positions={}", me, r.strategiesStopped(), r.positionsClosed());
        return r;
    }

    /**
     * The caller's stop for one strategy: it goes OFF for them and their open position in it is closed now (paper and
     * live). Their other strategies and other users trading the same strategy are not touched.
     */
    public StopResult stopStrategy(Long strategyId) {
        Long me = CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first"));
        strategies.get(strategyId);   // one the caller can see, or not found
        StopResult r = stop(me, strategyId, "stop");
        log.warn("STOP_STRATEGY user={} strategy={} positions={}", me, strategyId, r.positionsClosed());
        return r;
    }

    /** Switches the user's strategies OFF (one, or all when strategyId is null) and closes their open positions in them. */
    private StopResult stop(Long me, Long strategyId, String via) {
        int stopped = 0;
        for (StrategyLiveConfig cfg : configs.findByUserId(me)) {
            if (cfg.getMode() == TradingMode.OFF || (strategyId != null && !strategyId.equals(cfg.getStrategyId()))) continue;
            saveMode(cfg, TradingMode.OFF, null, via, false);
            stopped++;
        }
        List<String> details = new ArrayList<>();
        int closed = 0;
        LiveStrategyRunner r = runner.getIfAvailable();
        for (LivePosition p : positions.findByStatus(LivePosition.Status.OPEN)) {
            if (!me.equals(p.getUserId()) || (strategyId != null && !strategyId.equals(p.getStrategyId()))) continue;
            if (r == null) { details.add("position " + p.getId() + ": runner disabled, close it in Kite"); continue; }
            try {
                String did = r.exitOnStop(p.getId());   // under the runner's lock: never sold twice with a stop or target exit
                if (did != null) { details.add(did); closed++; }
            } catch (RuntimeException e) { details.add("position " + p.getId() + ": " + e.getMessage()); }
        }
        return new StopResult(stopped, closed, details);
    }

    public TradingControl releaseKillSwitch() {
        requireAdmin("turn the kill switch off");
        TradingControl c = controls.get();
        c.update(false, null, null, null, CurrentUser.id().orElse(null));
        return controls.save(c);
    }

    /**
     * These orders or positions without the PAPER ones of deleted strategies: a deleted strategy's paper trades are kept
     * as the record but shown to admins only. LIVE trades always stay: they were real money, so the user's live P&L
     * must count them. Manual orders (no strategy) stay.
     */
    public <T> java.util.List<T> withoutDeletedStrategies(java.util.List<T> rows, java.util.function.Function<T, Long> strategyOf,
                                                          java.util.function.Function<T, TradingMode> modeOf) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (T r : rows) if (strategyOf.apply(r) != null && modeOf.apply(r) != TradingMode.LIVE) ids.add(strategyOf.apply(r));
        if (ids.isEmpty()) return rows;
        java.util.Set<Long> deleted = strategyRepo.findDeletedIds(ids);
        return deleted.isEmpty() ? rows
                : rows.stream().filter(r -> modeOf.apply(r) == TradingMode.LIVE || !deleted.contains(strategyOf.apply(r))).toList();
    }

    /** Whether the caller is an ADMIN (non-admins only see paper trading). */
    public boolean isAdmin() { return CurrentUser.admin(); }

    /** The caller may trade live: an ADMIN, or a PREMIUM user who connected their own Kite or Angel One account. */
    public void requireLive() {
        Long id = CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.FORBIDDEN, "Log in to trade live"));
        String refusal = liveRefusal(id);
        if (refusal != null) throw new TradingException(HttpStatus.FORBIDDEN, refusal);
    }

    /** Why the user may not trade live (admin restriction first, then plan and own Kite/Angel One account, then compliance), or null. */
    public String liveRefusal(Long userId) {
        String access = orderService.accessRefusal(userId);
        if (access != null) return access;
        String broker = accounts.liveRefusal(userId);
        return broker != null ? broker : compliance.liveRefusal(userId);   // risk disclosure, and 2FA / verified PAN when required
    }

    /** Whether the caller may trade live (see {@link #requireLive}). */
    public boolean liveAllowed() { return CurrentUser.id().map(id -> liveRefusal(id) == null).orElse(false); }

    public void requireAdmin(String action) {
        Long id = CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.FORBIDDEN, "Only an ADMIN can " + action + " (send X-User)"));
        boolean admin = users.findById(id).map(u -> u.getRoles().stream().anyMatch(r -> "ADMIN".equals(r.getName()))).orElse(false);
        if (!admin) throw new TradingException(HttpStatus.FORBIDDEN, "Only an ADMIN can " + action);
    }
}
