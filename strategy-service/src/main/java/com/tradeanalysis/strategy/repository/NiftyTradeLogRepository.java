package com.tradeanalysis.strategy.repository;

import com.tradeanalysis.strategy.model.NiftyTradeLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface NiftyTradeLogRepository extends JpaRepository<NiftyTradeLog, Long> {
    List<NiftyTradeLog> findByBacktestResultId(Long backtestResultId);
    List<NiftyTradeLog> findByStrategyId(Long strategyId);
    List<NiftyTradeLog> findByStrategyIdAndTradeDateBetween(Long strategyId, LocalDate from, LocalDate to);
    List<NiftyTradeLog> findByTradeDate(LocalDate tradeDate);
}
