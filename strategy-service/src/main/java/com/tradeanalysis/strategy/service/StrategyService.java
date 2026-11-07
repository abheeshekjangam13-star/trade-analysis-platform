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

    public StrategyService(StrategyRepository stratRepo,
                           BacktestResultRepository btRepo,
                           NiftyTradeLogRepository niftyRepo,
                           BacktestEngine engine,
                           BacktestDailyReportRepository dailyRepo,
                           BacktestMonthlyStatRepository monthlyRepo,
                           com.tradeanalysis.strategy.user.SharedStrategies shared,
                           org.springframework.jdbc.core.JdbcTemplate jdbc) {
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
            if (stratRepo.countByCreatedByUserId(owner) >= plan.maxStrategies())
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
            try {
                com.tradeanalysis.strategy.engine.NiftyOpeningBreakoutEngine.validateParams(new com.tradeanalysis.common.indicator.Params(new java.util.HashMap<>(clean)));
            } catch (IllegalArgumentException e) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Invalid parameters: " + e.getMessage());
            }
        }
        return clean;
    }

    public static final int MAX_LOTS = 50;

    /**
     * Copies a strategy the caller can see (their own or a shared master strategy) into a new strategy owned by the
     * caller, with their own number of lots. Everything else stays the same. The name defaults to
     * "&lt;source&gt; (N lots)", made unique among the caller's strategies.
     */
    @Transactional
    public Strategy cloneWithLots(Long sourceId, int lots, String name) {
        Strategy src = get(sourceId);
        if (!"NIFTY_OPENING_BREAKOUT".equals(src.getType()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Only NIFTY_OPENING_BREAKOUT strategies trade in lots");
        if (lots < 1 || lots > MAX_LOTS)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Lots must be between 1 and " + MAX_LOTS);
        Long owner = CurrentUser.id().orElse(null);
        checkStrategyLimit(owner);
        String wanted = name == null || name.isBlank() ? src.getName() + " (" + lots + " lots)" : name.trim();
        if (name != null && !name.isBlank() && owner != null && stratRepo.existsByNameAndCreatedByUserId(wanted, owner))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "You already have a strategy named '" + wanted + "'");
        String unique = wanted;
        for (int n = 2; owner != null && stratRepo.existsByNameAndCreatedByUserId(unique, owner); n++) unique = wanted + " #" + n;

        Map<String, String> params = new java.util.LinkedHashMap<>(src.getParameters() == null ? Map.of() : src.getParameters());
        params.put("lots", String.valueOf(lots));
        String description = (src.getDescription() == null || src.getDescription().isBlank() ? "" : src.getDescription() + "\n\n")
                + "Cloned from #" + src.getId() + " (" + src.getName() + ") with " + lots + " lots.";
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
     * Deletes a strategy with its backtests (and their reports and trade logs) and its paper-trading history. Refused
     * while it is being traded or is in a portfolio, and when it has real (LIVE) trades on record, which are kept.
     */
    @Transactional
    public void delete(Long id) {
        Strategy s = editable(id);
        requireNotTrading(s, "delete");
        java.util.List<String> portfolios = jdbc.queryForList(
                "SELECT DISTINCT p.name FROM portfolio p JOIN portfolio_strategy ps ON ps.portfolio_id = p.id WHERE ps.strategy_id = ? "
                        + "UNION SELECT DISTINCT p.name FROM portfolio p JOIN portfolio_report r ON r.portfolio_id = p.id "
                        + "JOIN portfolio_report_item i ON i.portfolio_report_id = r.id WHERE i.strategy_id = ?", String.class, id, id);
        if (!portfolios.isEmpty())
            throw new StrategyBlockedException(StrategyBlockedException.Reason.IN_PORTFOLIO,
                    "'" + s.getName() + "' is part of " + (portfolios.size() == 1 ? "the portfolio '" + portfolios.get(0) + "'"
                            : "the portfolios " + String.join(", ", portfolios.stream().map(n -> "'" + n + "'").toList()))
                            + ". Delete " + (portfolios.size() == 1 ? "that portfolio" : "those portfolios") + " first, then try again.");
        Integer live = jdbc.queryForObject("SELECT COUNT(*) FROM live_position WHERE strategy_id = ? AND mode = 'LIVE'", Integer.class, id);
        if (live != null && live > 0)
            throw new StrategyBlockedException(StrategyBlockedException.Reason.HAS_LIVE_TRADES,
                    "'" + s.getName() + "' has placed real (live) trades, and their records must be kept, so it can't be "
                            + "deleted. You can rename it, or leave its trading off.");
        // Paper (simulated) trading history goes with it; the orders themselves stay in the order log.
        jdbc.update("DELETE FROM live_position WHERE strategy_id = ? AND mode = 'PAPER'", id);
        // Backtest daily reports and monthly stats go with their backtest (ON DELETE CASCADE).
        jdbc.update("DELETE FROM nifty_trade_log WHERE strategy_id = ? OR backtest_result_id IN (SELECT id FROM backtest_result WHERE strategy_id = ?)", id, id);
        jdbc.update("DELETE FROM nifty_position_state WHERE strategy_id = ?", id);
        jdbc.update("DELETE FROM backtest_result WHERE strategy_id = ?", id);
        stratRepo.delete(s);
    }

    /** A strategy the caller may see: their own, a shared master strategy, or any for an admin (else 404). */
    @Transactional(readOnly = true)
    public Strategy get(Long id) {
        Strategy s = load(id);
        if (!canSee(s)) throw new NotFoundException("Strategy not found: " + id);
        return s;
    }

    /** Any strategy, without the owner check: for the live runner, which trades for every user. */
    @Transactional(readOnly = true)
    public Strategy load(Long id) {
        return stratRepo.findById(id).orElseThrow(() -> new NotFoundException("Strategy not found: " + id));
    }

    public boolean canSee(Strategy s) { return CurrentUser.owns(s.getCreatedByUserId()) || shared.contains(s.getId()); }

    public boolean isShared(Long strategyId) { return shared.contains(strategyId); }

    public java.util.Set<Long> sharedIds() { return new java.util.TreeSet<>(shared.ids()); }

    @Transactional(readOnly = true)
    public List<Strategy> list() { return stratRepo.findAll().stream().filter(this::canSee).toList(); }

    @Transactional
    public BacktestResult backtest(Long strategyId, String symbol, int lookbackDays, double capital) {
        checkBacktestDays(lookbackDays);
        Strategy strategy = get(strategyId);
        BacktestEngine.BacktestOutput out = engine.run(strategy, symbol, lookbackDays, capital);
        BacktestResult result = new BacktestResult(
                strategy, symbol, Instant.now().minusSeconds((long) lookbackDays * 86400), Instant.now(),
                capital, out.finalCapital(), out.totalReturn(), out.maxDrawdown(),
                out.totalTrades(), out.winningTrades(), out.sharpeRatio(), out.signals());
        result.setRunContext(lookbackDays, CurrentUser.id().orElse(null));
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
