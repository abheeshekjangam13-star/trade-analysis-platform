package com.tradeanalysis.strategy.service;

import com.tradeanalysis.common.exception.NotFoundException;
import com.tradeanalysis.strategy.engine.BacktestEngine;
import com.tradeanalysis.strategy.model.BacktestResult;
import com.tradeanalysis.strategy.model.Strategy;
import com.tradeanalysis.strategy.repository.BacktestResultRepository;
import com.tradeanalysis.strategy.repository.StrategyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class StrategyService {

    private final StrategyRepository stratRepo;
    private final BacktestResultRepository btRepo;
    private final BacktestEngine engine;

    public StrategyService(StrategyRepository stratRepo,
                           BacktestResultRepository btRepo,
                           BacktestEngine engine) {
        this.stratRepo = stratRepo; this.btRepo = btRepo; this.engine = engine;
    }

    @Transactional
    public Strategy create(String name, String description, String type, Map<String, String> params) {
        return stratRepo.save(new Strategy(name, description, type, params));
    }

    @Transactional(readOnly = true)
    public Strategy get(Long id) {
        return stratRepo.findById(id).orElseThrow(() -> new NotFoundException("Strategy not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Strategy> list() { return stratRepo.findAll(); }

    @Transactional
    public BacktestResult backtest(Long strategyId, String symbol, int lookbackDays, double capital) {
        Strategy strategy = get(strategyId);
        BacktestEngine.BacktestOutput out = engine.run(strategy, symbol, lookbackDays, capital);
        BacktestResult result = new BacktestResult(
                strategy, symbol, Instant.now().minusSeconds((long) lookbackDays * 86400), Instant.now(),
                capital, out.finalCapital(), out.totalReturn(), out.maxDrawdown(),
                out.totalTrades(), out.winningTrades(), out.sharpeRatio(), out.signals());
        return btRepo.save(result);
    }

    @Transactional(readOnly = true)
    public List<BacktestResult> getBacktests(Long strategyId) { return btRepo.findByStrategyId(strategyId); }
}
