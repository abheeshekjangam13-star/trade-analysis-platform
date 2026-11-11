package com.tradeanalysis.strategy.service;

import com.tradeanalysis.strategy.report.BacktestDailyReport;
import com.tradeanalysis.strategy.report.BacktestDailyReportRepository;
import com.tradeanalysis.strategy.report.BacktestMonthlyStat;
import com.tradeanalysis.strategy.report.BacktestMonthlyStatRepository;
import com.tradeanalysis.strategy.report.ReportBuilder;
import com.tradeanalysis.strategy.user.CurrentUser;

import com.tradeanalysis.common.exception.NotFoundException;
import com.tradeanalysis.strategy.engine.BacktestEngine;
import com.tradeanalysis.strategy.model.BacktestResult;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.model.Strategy;
import com.tradeanalysis.strategy.repository.BacktestResultRepository;
import com.tradeanalysis.strategy.repository.NiftyTradeLogRepository;
import com.tradeanalysis.strategy.repository.StrategyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class StrategyService {

    private final StrategyRepository       stratRepo;
    private final BacktestResultRepository btRepo;
    private final NiftyTradeLogRepository  niftyRepo;
    private final BacktestEngine           engine;
    private final BacktestDailyReportRepository dailyRepo;
    private final BacktestMonthlyStatRepository monthlyRepo;
    private final com.tradeanalysis.strategy.user.SharedStrategies shared;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.tradeanalysis.strategy.user.DeleteAllowance deletes;

    public StrategyService(StrategyRepository stratRepo,
                           BacktestResultRepository btRepo,
                           NiftyTradeLogRepository niftyRepo,
                           BacktestEngine engine,
                           BacktestDailyReportRepository dailyRepo,
                           BacktestMonthlyStatRepository monthlyRepo,
                           com.tradeanalysis.strategy.user.SharedStrategies shared,
                           org.springframework.jdbc.core.JdbcTemplate jdbc,
                           com.tradeanalysis.strategy.user.DeleteAllowance deletes) {
        this.deletes = deletes;
        this.shared = shared;
        this.jdbc = jdbc;
        this.stratRepo = stratRepo;
        this.btRepo    = btRepo;
        this.niftyRepo = niftyRepo;
        this.engine    = engine;
        this.dailyRepo = dailyRepo;
        this.monthlyRepo = monthlyRepo;
    }

    @Transactional
    public Strategy create(String name, String description, String type, Map<String, String> params) {
        Long owner = CurrentUser.id().orElse(null);
        if (name != null && owner != null && stratRepo.existsByNameAndCreatedByUserId(name, owner))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "You already have a strategy named '" + name + "'");
        checkStrategyLimit(owner);
        params = cleanParams(type, params);
        Strategy strategy = new Strategy(name, description, type, params);
        strategy.setCreatedByUserId(owner);
        return stratRepo.save(strategy);
    }

    /** Refuses a new strategy when the caller already owns as many as their plan allows (admins: no limit). */
    private void checkStrategyLimit(Long owner) {
        if (owner == null) return;
        CurrentUser.limitedPlan().ifPresent(plan -> {
            if (stratRepo.countByCreatedByUserIdAndDeletedAtIsNull(owner) >= plan.maxStrategies())
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "You've reached the limit of your " + plan + " plan: " + plan.maxStrategies() + " strategies."
                                + upgradeFor(plan));
        });
    }

    private static String upgradeFor(com.tradeanalysis.strategy.user.Plan plan) {
        var next = java.util.Arrays.stream(com.tradeanalysis.strategy.user.Plan.values())
                .filter(p -> p.maxStrategies() > plan.maxStrategies()).findFirst();
        return next.map(p -> " Upgrade your account to " + p + " (₹" + p.pricePerMonth() + "/month) to create up to "
                + p.maxStrategies() + " strategies.").orElse("");
    }

    /** Refuses a look-back longer than the caller's plan allows (admins and scheduled jobs: no limit). */
    private static void checkBacktestDays(int lookbackDays) {
        if (lookbackDays < 1)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "lookbackDays must be at least 1");
        CurrentUser.limitedPlan().ifPresent(plan -> {
            if (lookbackDays > plan.maxBacktestDays())
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "Your " + plan + " plan backtests up to the last " + plan.maxBacktestDays() + " days"
                                + (plan == com.tradeanalysis.strategy.user.Plan.PREMIUM ? "." : ": upgrade for longer backtests."));
        });
    }

    /**
     * Drops empty values (an empty field means "use the default": e.g. no skipDays = trade every day) and, for
     * NIFTY_OPENING_BREAKOUT, refuses parameters the engine would reject, so a strategy that cannot run is never saved.
     */
    static Map<String, String> cleanParams(String type, Map<String, String> params) {
        Map<String, String> clean = new java.util.LinkedHashMap<>();
        if (params != null) params.forEach((k, v) -> { if (k != null && v != null && !v.isBlank()) clean.put(k, v.trim()); });
        if ("NIFTY_OPENING_BREAKOUT".equals(type)) {
            clean.remove("lotSize");   // the index decides the lot size; a value saved earlier is dropped
            try {
                var p = new com.tradeanalysis.common.indicator.Params(new java.util.HashMap<>(clean));
                com.tradeanalysis.strategy.engine.NiftyOpeningBreakoutEngine.validateParams(p);
                if (com.tradeanalysis.strategy.engine.PreviousRangeEngine.handles(p))
                    com.tradeanalysis.strategy.engine.PreviousRangeEngine.validateParams(p);
                if (com.tradeanalysis.strategy.engine.GapFillEngine.handles(p))
                    com.tradeanalysis.strategy.engine.GapFillEngine.validateParams(p);
            } catch (IllegalArgumentException e) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Invalid parameters: " + e.getMessage());
            }
        }
        return clean;
    }

    public static final int MAX_LOTS = 50;

    /** Copies a strategy with the caller's own number of lots only (see {@link #cloneStrategy}). */
    @Transactional
    public Strategy cloneWithLots(Long sourceId, int lots, String name) {
        return cloneStrategy(sourceId, name, null, null, lots);
    }

    /**
     * Copies a strategy the caller can see (their own, or any for an admin) into a new strategy owned by the caller.
     * Everything can be changed in the copy: name, description and the full parameters (null = the source's), and
     * {@code lots} on top of them. The type stays the same. The name defaults to "&lt;source&gt; (copy)" (or
     * "(N lots)" when only the lots change), made unique among the caller's strategies. Counts towards the plan's
     * strategy limit, like creating one.
     */
    @Transactional
    public Strategy cloneStrategy(Long sourceId, String name, String description, Map<String, String> params, Integer lots) {
        Strategy src = get(sourceId);
        Map<String, String> p = new java.util.LinkedHashMap<>(params != null ? params
                : src.getParameters() == null ? Map.of() : src.getParameters());
        if (lots != null) {
            if (!"NIFTY_OPENING_BREAKOUT".equals(src.getType()))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Only NIFTY_OPENING_BREAKOUT strategies trade in lots");
            if (lots < 1 || lots > MAX_LOTS)
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Lots must be between 1 and " + MAX_LOTS);
            p.put("lots", String.valueOf(lots));
        }
        p = cleanParams(src.getType(), p);
        boolean lotsOnly = params == null && lots != null;
        String fallback = src.getName() + (lotsOnly ? " (" + lots + " lots)" : " (copy)");
        String desc = description != null ? description
                : (src.getDescription() == null || src.getDescription().isBlank() ? "" : src.getDescription() + "\n\n")
                        + "Cloned from '" + src.getName() + "'" + (lotsOnly ? " with " + lots + " lots." : ".");
        return saveCopy(src, CurrentUser.id().orElse(null), name, fallback, desc, p);
    }

    /**
     * A strategy shared by an admin, accepted by the caller: their own copy with the same rules (see
     * StrategyShareService). Counts towards the caller's plan limit.
     */
    @Transactional
    public Strategy copyShared(Long sourceId, Long owner, String sharedBy) {
        Strategy src = load(sourceId);
        String desc = (src.getDescription() == null || src.getDescription().isBlank() ? "" : src.getDescription() + "\n\n")
                + "Shared with you by " + sharedBy + ".";
        return saveCopy(src, owner, null, src.getName(), desc,
                new java.util.LinkedHashMap<>(src.getParameters() == null ? Map.of() : src.getParameters()));
    }

    private Strategy saveCopy(Strategy src, Long owner, String name, String fallback, String description, Map<String, String> params) {
        checkStrategyLimit(owner);
        boolean named = name != null && !name.isBlank();
        String wanted = named ? name.trim() : fallback;
        if (named && owner != null && stratRepo.existsByNameAndCreatedByUserId(wanted, owner))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "You already have a strategy named '" + wanted + "'");
        String unique = wanted;
        for (int n = 2; owner != null && stratRepo.existsByNameAndCreatedByUserId(unique, owner); n++) unique = wanted + " #" + n;
        Strategy copy = new Strategy(unique, description, src.getType(), params);
        copy.setCreatedByUserId(owner);
        copy.setClonedFromStrategyId(src.getId());
        return stratRepo.save(copy);
    }

    /**
     * A strategy the caller may change or delete: their own (any plan), or any for an admin. Shared master strategies
     * are admin-only; someone else's private strategy is 404, as for reading it.
     */
    private Strategy editable(Long id) {
        Strategy s = get(id);
        if (!CurrentUser.unrestricted() && (shared.contains(s.getId()) || !CurrentUser.owns(s.getCreatedByUserId())))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Shared strategies can't be changed: clone it to make your own copy");
        return s;
    }

    /**
     * Refuses when the strategy is being traded: an open position first (it must exit before trading is turned off,
     * or nothing would manage it), then a PAPER/LIVE setting (the caller's own, or someone else's).
     */
    private void requireNotTrading(Strategy s, String action) {
        Long id = s.getId();
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM live_position WHERE strategy_id = ? AND status = 'OPEN'", Integer.class, id);
        if (open != null && open > 0)
            throw new StrategyBlockedException(StrategyBlockedException.Reason.OPEN_POSITION,
                    "'" + s.getName() + "' has an open trade, so you can't " + action + " it yet. Let the trade finish "
                            + "(it exits at its stop loss, target, or the 15:20 square-off), then turn trading off and try again.");
        Long me = CurrentUser.id().orElse(null);
        Integer mine = me == null ? 0 : jdbc.queryForObject(
                "SELECT COUNT(*) FROM strategy_user_mode WHERE strategy_id = ? AND user_id = ? AND mode <> 'OFF'", Integer.class, id, me);
        if (mine != null && mine > 0)
            throw new StrategyBlockedException(StrategyBlockedException.Reason.TRADING_ON,
                    "'" + s.getName() + "' is switched on for paper/live trading, so you can't " + action + " it yet. "
                            + "Turn its trading off first, then try again.");
        Integer others = jdbc.queryForObject("SELECT COUNT(*) FROM strategy_user_mode WHERE strategy_id = ? AND mode <> 'OFF'", Integer.class, id);
        if (others != null && others > 0)
            throw new StrategyBlockedException(StrategyBlockedException.Reason.TRADING_ON_BY_OTHERS,
                    "'" + s.getName() + "' is being traded by an admin, so you can't " + action + " it yet. Ask the admin to turn it off.");
    }

    /**
     * Renames a strategy, changes its description and/or replaces its parameters (null = keep). The type can't change.
     * Parameters can't change while the strategy is being traded. Past backtests keep the results of the old parameters.
     */
    @Transactional
    public Strategy update(Long id, String name, String description, Map<String, String> params) {
        CurrentUser.limitedPlan().ifPresent(plan -> {
            if (!plan.editStrategies()) {
                var next = plan.next();
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "Editing strategies isn't part of the " + plan + " plan."
                                + (next == null ? "" : " Upgrade to " + next + " (₹" + next.pricePerMonth()
                                + "/month) to edit your strategies, or create a new one."));
            }
        });
        Strategy s = editable(id);
        if (name != null) {
            String n = name.trim();
            if (n.isEmpty())
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Name can't be empty");
            if (!n.equals(s.getName()) && stratRepo.existsByNameAndCreatedByUserIdAndIdNot(n, s.getCreatedByUserId(), id))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                        "You already have a strategy named '" + n + "'");
            s.setName(n);
        }
        if (description != null) s.setDescription(description);
        if (params != null) {
            Map<String, String> clean = cleanParams(s.getType(), params);
            if (!clean.equals(s.getParameters())) {
                requireNotTrading(s, "change the parameters of");
                s.setParameters(clean);
            }
        }
        return stratRepo.save(s);
    }

    /**
     * Soft-deletes a strategy: it disappears from every list and page, but the row, its backtests, reports and trades
     * are kept for the record. Refused while it is being traded or is in a portfolio, and when the caller's plan has
     * used up its deletes (Free: 4).
     */
    @Transactional
    public void delete(Long id) {
        Strategy s = editable(id);
        requireNotTrading(s, "delete");
        java.util.List<String> portfolios = jdbc.queryForList(
                "SELECT DISTINCT p.name FROM portfolio p JOIN portfolio_strategy ps ON ps.portfolio_id = p.id "
                        + "WHERE ps.strategy_id = ? AND p.deleted_at IS NULL", String.class, id);
        if (!portfolios.isEmpty())
            throw new StrategyBlockedException(StrategyBlockedException.Reason.IN_PORTFOLIO,
                    "'" + s.getName() + "' is part of " + (portfolios.size() == 1 ? "the portfolio '" + portfolios.get(0) + "'"
                            : "the portfolios " + String.join(", ", portfolios.stream().map(n -> "'" + n + "'").toList()))
                            + ". Delete " + (portfolios.size() == 1 ? "that portfolio" : "those portfolios") + " first, then try again.");
        deletes.check();
        s.softDelete(CurrentUser.id().orElse(null), Instant.now());
        stratRepo.save(s);
    }

    /** A strategy the caller may see: their own, or any for an admin (else 404). Master strategies are admin-only. */
    @Transactional(readOnly = true)
    public Strategy get(Long id) {
        Strategy s = load(id);
        if (!canSee(s)) throw new NotFoundException("Strategy not found: " + id);
        return s;
    }

    /** Any strategy, deleted ones included, or null: for showing a share's strategy whatever happened to it. */
    @Transactional(readOnly = true)
    public Strategy findAny(Long id) {
        return stratRepo.findById(id).filter(s -> !s.isDeleted()).orElse(null);
    }

    /** Any strategy, without the owner check: for the live runner, which trades for every user. */
    @Transactional(readOnly = true)
    public Strategy load(Long id) {
        return stratRepo.findById(id).filter(s -> !s.isDeleted())
                .orElseThrow(() -> new NotFoundException("Strategy not found: " + id));
    }

    public boolean canSee(Strategy s) {
        return CurrentUser.owns(s.getCreatedByUserId()) && (CurrentUser.unrestricted() || !shared.contains(s.getId()));
    }

    public boolean isShared(Long strategyId) { return shared.contains(strategyId); }

    /** The master strategy ids, for admins (everyone else can't see them, so gets none). */
    public java.util.Set<Long> sharedIds() {
        return CurrentUser.unrestricted() ? new java.util.TreeSet<>(shared.ids()) : java.util.Set.of();
    }

    @Transactional(readOnly = true)
    public List<Strategy> list() { return stratRepo.findByDeletedAtIsNull().stream().filter(this::canSee).toList(); }

    @Transactional
    public BacktestResult backtest(Long strategyId, String symbol, int lookbackDays, double capital) {
        Strategy strategy = get(strategyId);
        checkBacktestDays(lookbackDays);   // the plan's limit first, then the index
        if ("NIFTY_OPENING_BREAKOUT".equals(strategy.getType())) {
            var u = com.tradeanalysis.common.market.Underlying.of(strategy.getParameters() == null ? null : strategy.getParameters().get("underlying"));
            if (symbol == null || symbol.isBlank()) symbol = u.indexSymbol;
            else if (!symbol.trim().equalsIgnoreCase(u.indexSymbol))
                throw new IllegalArgumentException("This strategy trades " + (u == com.tradeanalysis.common.market.Underlying.BANKNIFTY ? "Bank Nifty" : "NIFTY")
                        + ": backtest it on " + u.indexSymbol + ", not " + symbol);
        }
        BacktestEngine.BacktestOutput out = engine.run(strategy, symbol, lookbackDays, capital);
        BacktestResult result = new BacktestResult(
                strategy, symbol, Instant.now().minusSeconds((long) lookbackDays * 86400), Instant.now(),
                capital, out.finalCapital(), out.totalReturn(), out.maxDrawdown(),
                out.totalTrades(), out.winningTrades(), out.sharpeRatio(), out.signals());
        result.setRunContext(lookbackDays, CurrentUser.id().orElse(null));
        result.setSkippedTrades(out.skippedTrades());
        BacktestResult saved = btRepo.save(result);

        if (!out.tradeLogs().isEmpty()) {
            out.tradeLogs().forEach(log -> log.setBacktestResultId(saved.getId()));
            niftyRepo.saveAll(out.tradeLogs());
        }
        // Stored reports for the UI: one row per trading day and per month.
        dailyRepo.saveAll(ReportBuilder.daily(saved.getId(), out.days(), out.tradeLogs()));
        monthlyRepo.saveAll(ReportBuilder.monthly(saved.getId(), out.days(), out.tradeLogs()));

        return saved;
    }

    @Transactional(readOnly = true)
    public List<BacktestResult> getBacktests(Long strategyId) {
        get(strategyId);
        return CurrentUser.unrestricted() ? btRepo.findByStrategyId(strategyId)
                : btRepo.findByStrategyIdAndRunByUserId(strategyId, CurrentUser.id().orElseThrow());
    }

    public static final List<Integer> PERFORMANCE_WINDOWS = List.of(30, 90, 180, 360);

    public record WindowStat(Long backtestId, double returnPct, double netPnl, double capital, int trades, int wins,
                             double maxDrawdown, Instant runAt) {}

    public record Performance(Long strategyId, Map<Integer, WindowStat> windows) {}

    /**
     * For every strategy the caller can see: the latest backtest the caller ran (admins: anyone's) for each of the
     * 30/90/180/360-day windows. Strategies without such backtests are left out.
     */
    @Transactional(readOnly = true)
    public List<Performance> performance() {
        Long runBy = CurrentUser.unrestricted() ? null : CurrentUser.id().orElseThrow();
        java.util.Set<Long> visible = new java.util.HashSet<>(list().stream().map(Strategy::getId).toList());
        Map<Long, Map<Integer, WindowStat>> out = new java.util.LinkedHashMap<>();
        for (Object[] r : btRepo.summaries(PERFORMANCE_WINDOWS, runBy)) {
            Long strategyId = (Long) r[0];
            if (!visible.contains(strategyId)) continue;
            Map<Integer, WindowStat> w = out.computeIfAbsent(strategyId, k -> new java.util.TreeMap<>());
            Integer window = (Integer) r[1];
            if (w.containsKey(window)) continue;   // rows are newest first: keep the latest per window
            double capital = ((Number) r[4]).doubleValue(), end = ((Number) r[5]).doubleValue();
            w.put(window, new WindowStat((Long) r[2], ((Number) r[3]).doubleValue(), end - capital, capital,
                    ((Number) r[6]).intValue(), ((Number) r[7]).intValue(), ((Number) r[8]).doubleValue(), (Instant) r[9]));
        }
        return out.entrySet().stream().map(e -> new Performance(e.getKey(), e.getValue())).toList();
    }

    /** A backtest the caller ran (admins: any), else 404. */
    @Transactional(readOnly = true)
    public BacktestResult getBacktest(Long backtestId) {
        BacktestResult b = btRepo.findById(backtestId).orElseThrow(() -> new NotFoundException("Backtest not found: " + backtestId));
        if (!CurrentUser.owns(b.getRunByUserId())) throw new NotFoundException("Backtest not found: " + backtestId);
        return b;
    }

    @Transactional(readOnly = true)
    public List<BacktestDailyReport> getDailyReport(Long backtestId) {
        getBacktest(backtestId);
        return dailyRepo.findByBacktestResultIdOrderByTradeDate(backtestId);
    }

    @Transactional(readOnly = true)
    public List<BacktestMonthlyStat> getMonthlyStats(Long backtestId) {
        getBacktest(backtestId);
        return monthlyRepo.findByBacktestResultIdOrderByPeriodMonth(backtestId);
    }

    @Transactional(readOnly = true)
    public List<NiftyTradeLog> getNiftyTradeLogs(Long backtestResultId) {
        getBacktest(backtestResultId);
        return niftyRepo.findByBacktestResultId(backtestResultId);
    }

    /** Trades of the caller's own backtests of a strategy (admins: everyone's). */
    @Transactional(readOnly = true)
    public List<NiftyTradeLog> getNiftyTradeLogsByStrategy(Long strategyId) {
        get(strategyId);
        return mine(niftyRepo.findByStrategyId(strategyId));
    }

    @Transactional(readOnly = true)
    public List<NiftyTradeLog> getNiftyTradeLogsByDate(LocalDate date) {
        return mine(niftyRepo.findByTradeDate(date));
    }

    /** Trades with a trade date in [from, to], both inclusive and optional; neither returns every trade. */
    @Transactional(readOnly = true)
    public List<NiftyTradeLog> getNiftyTradeLogsBetween(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "from (" + from + ") is after to (" + to + ")");
        LocalDate lo = from == null ? LocalDate.of(1900, 1, 1) : from;
        LocalDate hi = to == null ? LocalDate.of(9999, 12, 31) : to;
        return mine(from == null && to == null ? niftyRepo.findAll() : niftyRepo.findByTradeDateBetween(lo, hi));
    }

    private List<NiftyTradeLog> mine(List<NiftyTradeLog> trades) {
        if (CurrentUser.unrestricted()) return trades;
        java.util.Set<Long> own = new java.util.HashSet<>(btRepo.idsRunBy(CurrentUser.id().orElseThrow()));
        return trades.stream().filter(t -> t.getBacktestResultId() != null && own.contains(t.getBacktestResultId())).toList();
    }
}
