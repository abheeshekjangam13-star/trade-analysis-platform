package com.tradeanalysis.strategy.repository;

import com.tradeanalysis.strategy.model.BacktestResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BacktestResultRepository extends JpaRepository<BacktestResult, Long> {
    List<BacktestResult> findByStrategyId(Long strategyId);
    List<BacktestResult> findByStrategyIdAndRunByUserId(Long strategyId, Long runByUserId);
    @org.springframework.data.jpa.repository.Query("select b.id from BacktestResult b where b.runByUserId = :userId")
    List<Long> idsRunBy(@org.springframework.data.repository.query.Param("userId") Long userId);
    List<BacktestResult> findBySymbol(String symbol);
}
