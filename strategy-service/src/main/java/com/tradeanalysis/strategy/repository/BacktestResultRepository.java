package com.tradeanalysis.strategy.repository;

import com.tradeanalysis.strategy.model.BacktestResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BacktestResultRepository extends JpaRepository<BacktestResult, Long> {
    List<BacktestResult> findByStrategyId(Long strategyId);
    List<BacktestResult> findBySymbol(String symbol);
}
