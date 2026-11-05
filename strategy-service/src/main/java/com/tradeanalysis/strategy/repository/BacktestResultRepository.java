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

    /** Summary columns of backtests with one of these look-backs, newest first (runBy null = everyone's). */
    @org.springframework.data.jpa.repository.Query("""
            select b.strategy.id, b.lookbackDays, b.id, b.totalReturn, b.initialCapital, b.finalCapital,
                   b.totalTrades, b.winningTrades, b.maxDrawdown, b.createdAt
            from BacktestResult b
            where b.lookbackDays in :windows and (:runBy is null or b.runByUserId = :runBy)
            order by b.createdAt desc, b.id desc""")
    List<Object[]> summaries(@org.springframework.data.repository.query.Param("windows") java.util.Collection<Integer> windows,
                             @org.springframework.data.repository.query.Param("runBy") Long runBy);
}
