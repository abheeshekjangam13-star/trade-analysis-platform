package com.tradeanalysis.strategy.portfolio;

import com.tradeanalysis.common.exception.NotFoundException;
import com.tradeanalysis.strategy.engine.CandleMemo;
import com.tradeanalysis.strategy.model.BacktestResult;
import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.service.StrategyService;
import com.tradeanalysis.strategy.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import jakarta.annotation.PreDestroy;

@Service
public class PortfolioService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PortfolioService.class);

    private final PortfolioRepository portfolios;
    private final PortfolioReportRepository reports;
    private final StrategyService strategies;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.tradeanalysis.strategy.user.DeleteAllowance deletes;
    private final com.tradeanalysis.strategy.estimate.CapitalFit capitalFit;

    public PortfolioService(PortfolioRepository portfolios, PortfolioReportRepository reports, StrategyService strategies,
                            org.springframework.jdbc.core.JdbcTemplate jdbc, com.tradeanalysis.strategy.user.DeleteAllowance deletes,
                            com.tradeanalysis.strategy.estimate.CapitalFit capitalFit) {
        this.portfolios = portfolios; this.reports = reports; this.strategies = strategies; this.jdbc = jdbc;
        this.deletes = deletes; this.capitalFit = capitalFit;
    }

    /** What an update did: the portfolio, and how many stored reports were deleted (strategies changed = start fresh). */
    public record UpdateResult(Portfolio portfolio, int reportsDeleted, boolean strategiesChanged) {}

    /**
     * Renames a portfolio, changes its description and/or its strategies (null = keep). Changing the strategies deletes
     * every stored report of the portfolio and the backtests those reports ran, since they no longer match: run a fresh
     * report afterwards.
     */
    @Transactional
    public UpdateResult update(Long id, String name, String description, Collection<Long> strategyIds) {
        Portfolio p = get(id);
        if (name != null) {
            String n = name.trim();
            if (n.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name can't be empty");
            if (!n.equalsIgnoreCase(p.getName()) && portfolios.existsByNameIgnoreCaseAndCreatedByUserId(n, p.getCreatedByUserId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have a portfolio named '" + n + "'");
            p.setName(n);
        }
        if (description != null) p.setDescription(description);
        int deleted = 0;
        boolean changed = false;
        if (strategyIds != null) {
            if (strategyIds.isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A portfolio needs at least one strategy");
            java.util.Set<Long> wanted = new LinkedHashSet<>(strategyIds);
            wanted.forEach(strategies::get);   // 404 for unknown strategies and other users' strategies
            if (!wanted.equals(new java.util.HashSet<>(p.getStrategyIds()))) {
                deleted = deleteReportData(id);
                p.setStrategyIds(wanted);
                changed = true;
            }
        }
        return new UpdateResult(portfolios.save(p), deleted, changed);
    }

    /**
     * Soft-deletes a portfolio: it disappears from every list and page, but the row and its stored reports are kept.
     * Its strategies stay (and can be deleted afterwards). Counts towards the plan's delete allowance (Free: 4).
     */
    @Transactional
    public void delete(Long id) {
        Portfolio p = get(id);
        deletes.check();
        p.softDelete(CurrentUser.id().orElse(null), java.time.Instant.now());
        portfolios.save(p);
    }

    /**
     * Deletes every stored report of the portfolio (their items cascade) and the backtests those reports ran (with their
     * daily/monthly reports and trade logs), unless another portfolio's report also uses them. Returns the reports deleted.
     */
    private int deleteReportData(Long portfolioId) {
        List<Long> backtests = jdbc.queryForList(
                "SELECT DISTINCT i.backtest_result_id FROM portfolio_report_item i JOIN portfolio_report r ON r.id = i.portfolio_report_id "
                        + "WHERE r.portfolio_id = ?", Long.class, portfolioId);
        int deleted = jdbc.update("DELETE FROM portfolio_report WHERE portfolio_id = ?", portfolioId);
        deleteUnusedBacktests(backtests);
        return deleted;
    }

    @Transactional
    public Portfolio create(String name, String description, Collection<Long> strategyIds) {
        if (name == null || name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: name");
        if (strategyIds == null || strategyIds.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: strategyIds");
        if (portfolios.existsByNameIgnoreCaseAndCreatedByUserId(name.trim(), CurrentUser.id().orElse(null)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Portfolio already exists: " + name.trim());
        strategyIds.forEach(strategies::get);   // 404 for unknown strategies and other users' strategies
        return portfolios.save(new Portfolio(name.trim(), description, new LinkedHashSet<>(strategyIds),
                CurrentUser.id().orElse(null)));
    }

    @Transactional(readOnly = true)
    public Portfolio get(Long id) {
        Portfolio p = portfolios.findById(id).filter(x -> !x.isDeleted())
                .orElseThrow(() -> new NotFoundException("Portfolio not found: " + id));
        if (!CurrentUser.owns(p.getCreatedByUserId())) throw new NotFoundException("Portfolio not found: " + id);
        return p;
    }

    /** The caller's portfolios (admins: everyone's). */
    @Transactional(readOnly = true)
    public List<Portfolio> list() {
        return portfolios.findAll().stream().filter(p -> !p.isDeleted() && CurrentUser.owns(p.getCreatedByUserId())).toList();
    }

    @Transactional(readOnly = true)
    public List<PortfolioReport> reports(Long portfolioId) {
        get(portfolioId);
        return reports.findByPortfolioIdOrderByCreatedAtDescWindowDaysAsc(portfolioId);
    }

    /**
     * Runs a fresh backtest of every strategy for each window, combines their trades and stores one report per
     * window. Backtests (with their daily/monthly reports) are saved as they complete.
     *
     * <p>The strategies run {@link #PARALLEL} at a time, all reading one shared copy of the candles ({@link CandleMemo}).
     * Each strategy runs its windows longest first: the long window stores every day it simulates (BacktestDayCache), so
     * the shorter ones only reuse them.
     */
    public List<PortfolioReport> run(Long portfolioId, List<Integer> windows, double capital, String symbol) {
        Portfolio portfolio = get(portfolioId);
        if (windows == null || windows.isEmpty() || windows.stream().anyMatch(w -> w == null || w < 1 || w > 3650))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "windows must be day counts between 1 and 3650");
        Long user = CurrentUser.id().orElse(null);
        LocalDate today = LocalDate.now(IST);
        // Blank = each strategy on the index it trades (NIFTY or Bank Nifty), so one portfolio can mix them.
        boolean ownIndex = symbol == null || symbol.isBlank();
        String label = ownIndex ? "Each strategy's index" : symbol.trim();
        Map<Long, Map<Integer, BacktestResult>> backtests = backtestAll(portfolio.getStrategyIds(), windows,
                ownIndex ? null : symbol, capital);
        List<PortfolioReport> saved = new ArrayList<>();
        for (int window : windows) {
            // All the strategies' trades share one capital pool. A trade that the free capital can't buy at its entry
            // is left out, as in a single backtest (CapitalFit).
            List<NiftyTradeLog> all = new ArrayList<>();
            Map<Long, Long> strategyOfBacktest = new LinkedHashMap<>();
            for (Long strategyId : portfolio.getStrategyIds()) {
                BacktestResult bt = backtests.get(strategyId).get(window);
                all.addAll(strategies.getNiftyTradeLogs(bt.getId()));
                strategyOfBacktest.put(bt.getId(), strategyId);
            }
            com.tradeanalysis.strategy.estimate.CapitalFit.Fit fit = capitalFit.fit(all, capital);
            List<NiftyTradeLog> kept = fit.kept();
            storeFit(kept, fit.left());
            List<PortfolioReportItem> items = new ArrayList<>();
            for (Map.Entry<Long, Long> e : strategyOfBacktest.entrySet()) {
                List<NiftyTradeLog> mine = kept.stream().filter(t -> e.getKey().equals(t.getBacktestResultId())).toList();
                items.add(new PortfolioReportItem(e.getValue(), e.getKey(), PortfolioMath.summarize(mine)));
            }
            PortfolioReport made = new PortfolioReport(portfolioId, window, label, today.minusDays(window), today,
                    capital, PortfolioMath.summarize(kept), PortfolioMath.capitalUse(kept), fit.skipped(), user, items);
            made.setCapitalToFitAll(PortfolioMath.capitalToFitAll(all));   // kept and left out, at the lots asked for
            PortfolioReport report = reports.save(made);
            replaceOlderReports(report);
            saved.add(report);
        }
        return saved;
    }

    /** Strategies backtested at once in a report: enough to overlap the waits on the database and market-data. */
    static final int PARALLEL = 3;
    private final ExecutorService pool = Executors.newFixedThreadPool(PARALLEL, r -> {
        Thread t = new Thread(r, "portfolio-backtest");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void stopPool() { pool.shutdownNow(); }

    /** Every strategy's backtest for every window, as strategy → window → backtest. Fails if any backtest fails. */
    private Map<Long, Map<Integer, BacktestResult>> backtestAll(Collection<Long> strategyIds, List<Integer> windows, String symbol,
                                                               double capital) {
        List<Integer> longestFirst = windows.stream().distinct().sorted(Comparator.reverseOrder()).toList();
        CandleMemo candles = new CandleMemo();
        Map<Long, Future<Map<Integer, BacktestResult>>> running = new LinkedHashMap<>();
        for (Long strategyId : new LinkedHashSet<>(strategyIds)) {
            running.put(strategyId, pool.submit(CurrentUser.carry(() -> candles.with(() -> {
                Map<Integer, BacktestResult> byWindow = new HashMap<>();
                for (int window : longestFirst) byWindow.put(window, strategies.backtest(strategyId, symbol, window, capital));
                return byWindow;
            }))));
        }
        Map<Long, Map<Integer, BacktestResult>> done = new HashMap<>();
        try {
            for (Map.Entry<Long, Future<Map<Integer, BacktestResult>>> e : running.entrySet()) done.put(e.getKey(), e.getValue().get());
        } catch (ExecutionException e) {
            running.values().forEach(f -> f.cancel(true));
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            running.values().forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backtesting", e);
        }
        log.info("PORTFOLIO_BACKTESTS strategies={} windows={} candleFetches={}", done.size(), longestFirst, candles.fetches());
        return done;
    }

    /**
     * Stores the portfolio's capital fit on its trades, so the Trades page shows what the report counted: the lots (and
     * P&L) each kept trade was taken with, and the trades left out for lack of capital, marked CAPITAL. The backtests a
     * report runs are its own (fresh per run), so this changes no other report.
     */
    private void storeFit(List<NiftyTradeLog> kept, List<NiftyTradeLog> left) {
        List<Object[]> rows = new ArrayList<>();
        for (NiftyTradeLog t : kept) rows.add(new Object[]{t.getLots(), t.getEstimatedPnl(), null, t.getId()});
        for (NiftyTradeLog t : left) {
            t.markSkipped("CAPITAL");
            rows.add(new Object[]{t.getLots(), t.getEstimatedPnl(), "CAPITAL", t.getId()});
        }
        if (!rows.isEmpty())
            jdbc.batchUpdate("UPDATE nifty_trade_log SET lots = ?, estimated_pnl = ?, skip_reason = ? WHERE id = ?", rows);
    }

    /**
     * A re-run replaces the portfolio's earlier reports for the same window, capital and index: they are deleted with
     * the backtests they ran (and those backtests' trade logs and daily/monthly reports), unless another report still
     * uses a backtest. Called after the new report is saved, so a failed run leaves the old one in place.
     */
    private void replaceOlderReports(PortfolioReport report) {
        List<Long> older = jdbc.queryForList("SELECT id FROM portfolio_report WHERE portfolio_id = ? AND window_days = ? "
                + "AND capital = ? AND symbol = ? AND id <> ?", Long.class, report.getPortfolioId(), report.getWindowDays(),
                report.getCapital(), report.getSymbol(), report.getId());
        for (Long id : older) {
            List<Long> backtests = jdbc.queryForList(
                    "SELECT DISTINCT backtest_result_id FROM portfolio_report_item WHERE portfolio_report_id = ?", Long.class, id);
            jdbc.update("DELETE FROM portfolio_report WHERE id = ?", id);   // its items cascade
            deleteUnusedBacktests(backtests);
        }
    }

    /** Deletes these backtests with their trade logs (daily/monthly reports cascade), skipping any a report still uses. */
    private void deleteUnusedBacktests(List<Long> backtests) {
        for (Long bt : backtests) {
            Integer elsewhere = jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_report_item WHERE backtest_result_id = ?", Integer.class, bt);
            if (elsewhere != null && elsewhere > 0) continue;
            jdbc.update("DELETE FROM nifty_trade_log WHERE backtest_result_id = ?", bt);
            jdbc.update("DELETE FROM backtest_result WHERE id = ?", bt);   // daily/monthly reports cascade
        }
    }
}
