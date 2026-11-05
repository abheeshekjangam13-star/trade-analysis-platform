package com.tradeanalysis.strategy.portfolio;

import com.tradeanalysis.common.exception.NotFoundException;
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

@Service
public class PortfolioService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PortfolioRepository portfolios;
    private final PortfolioReportRepository reports;
    private final StrategyService strategies;

    public PortfolioService(PortfolioRepository portfolios, PortfolioReportRepository reports, StrategyService strategies) {
        this.portfolios = portfolios; this.reports = reports; this.strategies = strategies;
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
        Portfolio p = portfolios.findById(id).orElseThrow(() -> new NotFoundException("Portfolio not found: " + id));
        if (!CurrentUser.owns(p.getCreatedByUserId())) throw new NotFoundException("Portfolio not found: " + id);
        return p;
    }

    /** The caller's portfolios (admins: everyone's). */
    @Transactional(readOnly = true)
    public List<Portfolio> list() { return portfolios.findAll().stream().filter(p -> CurrentUser.owns(p.getCreatedByUserId())).toList(); }

    @Transactional(readOnly = true)
    public List<PortfolioReport> reports(Long portfolioId) {
        get(portfolioId);
        return reports.findByPortfolioIdOrderByCreatedAtDescWindowDaysAsc(portfolioId);
    }

    /**
     * Runs a fresh backtest of every strategy for each window, combines their trades and stores one report per
     * window. Backtests (with their daily/monthly reports) are saved as they complete.
     */
    public List<PortfolioReport> run(Long portfolioId, List<Integer> windows, double capital, String symbol) {
        Portfolio portfolio = get(portfolioId);
        if (windows == null || windows.isEmpty() || windows.stream().anyMatch(w -> w == null || w < 1 || w > 3650))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "windows must be day counts between 1 and 3650");
        Long user = CurrentUser.id().orElse(null);
        LocalDate today = LocalDate.now(IST);
        List<PortfolioReport> saved = new ArrayList<>();
        for (int window : windows) {
            List<NiftyTradeLog> all = new ArrayList<>();
            List<PortfolioReportItem> items = new ArrayList<>();
            for (Long strategyId : portfolio.getStrategyIds()) {
                BacktestResult bt = strategies.backtest(strategyId, symbol, window, capital);
                List<NiftyTradeLog> trades = strategies.getNiftyTradeLogs(bt.getId());
                all.addAll(trades);
                items.add(new PortfolioReportItem(strategyId, bt.getId(), PortfolioMath.summarize(trades)));
            }
            saved.add(reports.save(new PortfolioReport(portfolioId, window, symbol, today.minusDays(window), today,
                    capital, PortfolioMath.summarize(all), user, items)));
        }
        return saved;
    }
}
