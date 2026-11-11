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

    private final StrategyLiveConfigRepository configs;
    private final TradingControlRepository controls;
    private final TradeOrderRepository orders;
    private final LivePositionRepository positions;
    private final OrderService orderService;
    private final StrategyService strategies;
    private final AppUserRepository users;
    private final ObjectProvider<LiveStrategyRunner> runner;
    private final KiteAccounts accounts;
    private final com.tradeanalysis.strategy.repository.StrategyRepository strategyRepo;

    public TradingService(StrategyLiveConfigRepository configs, TradingControlRepository controls, TradeOrderRepository orders,
                          LivePositionRepository positions, OrderService orderService, StrategyService strategies,
                          AppUserRepository users, ObjectProvider<LiveStrategyRunner> runner, KiteAccounts accounts,
                          com.tradeanalysis.strategy.repository.StrategyRepository strategyRepo) {
        this.configs = configs; this.controls = controls; this.orders = orders; this.positions = positions;
        this.orderService = orderService; this.strategies = strategies; this.users = users; this.runner = runner;
        this.accounts = accounts; this.strategyRepo = strategyRepo;
    }

    public record StrategyMode(Long strategyId, String name, String type, String direction, TradingMode mode, String product) {}

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

    /** Sets the caller's own mode: OFF/PAPER for GOLD+ users; LIVE needs PREMIUM + their own Kite account (or ADMIN) and live trading enabled. */
    public StrategyMode setMode(Long strategyId, TradingMode mode, String product) {
        Strategy s = strategies.get(strategyId);
        if (mode != TradingMode.OFF && CurrentUser.limitedPlan().map(p -> !p.paperTrading()).orElse(false))
            throw new TradingException(HttpStatus.FORBIDDEN, "Your plan is " + CurrentUser.limitedPlan().get()
                    + ". Upgrade to GOLD for paper trading (you can always turn trading off).");
        if (mode != TradingMode.OFF && !"NIFTY_OPENING_BREAKOUT".equals(s.getType()))
            throw new TradingException("Only NIFTY_OPENING_BREAKOUT strategies can trade live");
        if (product != null && !Set.of("MIS", "NRML").contains(product)) throw new TradingException("Product must be MIS or NRML");
        if (mode == TradingMode.LIVE) {
            requireLive();
            if (!orderService.liveEnabled())
                throw new TradingException("Live trading is disabled on this server (start strategy-service with TRADING_LIVE_ENABLED=true)");
        }
        if (mode != TradingMode.OFF && !CurrentUser.admin() && currentMaxDayLoss() == null)
            throw new TradingException("Set your max day loss first (Trading → Max day loss): it caps your loss for a day "
                    + "across all your strategies together");
        Long me = CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first"));
        StrategyLiveConfig c = configs.findByStrategyIdAndUserId(strategyId, me).orElseGet(() -> new StrategyLiveConfig(strategyId, me));
        c.set(mode, product);
        configs.save(c);
        return strategyModes().stream().filter(m -> m.strategyId().equals(strategyId)).findFirst().orElseThrow();
    }

    /**
     * Sets the caller's mode for several strategies at once (e.g. every strategy of a portfolio). The plan and the
     * live checks apply to all; a strategy that can't take the mode is reported and skipped.
     */
    public record BulkResult(List<StrategyMode> changed, List<Map<String, Object>> skipped) {}

    public BulkResult setModes(Collection<Long> strategyIds, TradingMode mode, String product) {
        if (mode == null) throw new TradingException("Mode must be OFF, PAPER or LIVE");
        List<StrategyMode> changed = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(strategyIds)) {
            try {
                changed.add(setMode(id, mode, product));
            } catch (TradingException e) {
                // Plan, live-permission and day-loss refusals hold for every strategy: report them once.
                String reason = String.valueOf(e.getReason());
                if (e.getStatusCode().value() == 403 || reason.startsWith("Set your max day loss")
                        || reason.startsWith("Live trading is disabled")) throw e;
                skipped.add(Map.of("strategyId", id, "reason", reason));
            } catch (RuntimeException e) {
                skipped.add(Map.of("strategyId", id, "reason", String.valueOf(e.getMessage())));
            }
        }
        return new BulkResult(changed, skipped);
    }

    /** The caller's own max day loss (null = not set). */
    public Double currentMaxDayLoss() {
        return CurrentUser.id().flatMap(users::findById).map(com.tradeanalysis.strategy.user.AppUser::getMaxDayLoss).orElse(null);
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
            cfg.set(TradingMode.OFF, null);
            configs.save(cfg);
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

    public TradingControl releaseKillSwitch() {
        requireAdmin("turn the kill switch off");
        TradingControl c = controls.get();
        c.update(false, null, null, null, CurrentUser.id().orElse(null));
        return controls.save(c);
    }

    /**
     * These orders or positions without those of deleted strategies: a deleted strategy's paper and live trades are kept
     * as the record but shown to admins only. Manual orders (no strategy) stay.
     */
    public <T> java.util.List<T> withoutDeletedStrategies(java.util.List<T> rows, java.util.function.Function<T, Long> strategyOf) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (T r : rows) if (strategyOf.apply(r) != null) ids.add(strategyOf.apply(r));
        if (ids.isEmpty()) return rows;
        java.util.Set<Long> deleted = strategyRepo.findDeletedIds(ids);
        return deleted.isEmpty() ? rows : rows.stream().filter(r -> !deleted.contains(strategyOf.apply(r))).toList();
    }

    /** Whether the caller is an ADMIN (non-admins only see paper trading). */
    public boolean isAdmin() { return CurrentUser.admin(); }

    /** The caller may trade live: an ADMIN, or a PREMIUM user who connected their own Kite account. */
    public void requireLive() {
        Long id = CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.FORBIDDEN, "Log in to trade live"));
        String refusal = accounts.liveRefusal(id);
        if (refusal != null) throw new TradingException(HttpStatus.FORBIDDEN, refusal);
    }

    /** Whether the caller may trade live (see {@link #requireLive}). */
    public boolean liveAllowed() { return CurrentUser.id().map(id -> accounts.liveRefusal(id) == null).orElse(false); }

    public void requireAdmin(String action) {
        Long id = CurrentUser.id().orElseThrow(() -> new TradingException(HttpStatus.FORBIDDEN, "Only an ADMIN can " + action + " (send X-User)"));
        boolean admin = users.findById(id).map(u -> u.getRoles().stream().anyMatch(r -> "ADMIN".equals(r.getName()))).orElse(false);
        if (!admin) throw new TradingException(HttpStatus.FORBIDDEN, "Only an ADMIN can " + action);
    }
}
