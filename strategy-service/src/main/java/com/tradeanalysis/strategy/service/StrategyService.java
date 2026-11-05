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

    public StrategyService(StrategyRepository stratRepo,
                           BacktestResultRepository btRepo,
                           NiftyTradeLogRepository niftyRepo,
                           BacktestEngine engine,
                           BacktestDailyReportRepository dailyRepo,
                           BacktestMonthlyStatRepository monthlyRepo,
                           com.tradeanalysis.strategy.user.SharedStrategies shared) {
        this.shared = shared;
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
        Strategy strategy = new Strategy(name, description, type, params);
        strategy.setCreatedByUserId(owner);
        return stratRepo.save(strategy);
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

    private List<NiftyTradeLog> mine(List<NiftyTradeLog> trades) {
        if (CurrentUser.unrestricted()) return trades;
        java.util.Set<Long> own = new java.util.HashSet<>(btRepo.idsRunBy(CurrentUser.id().orElseThrow()));
        return trades.stream().filter(t -> t.getBacktestResultId() != null && own.contains(t.getBacktestResultId())).toList();
    }
}
