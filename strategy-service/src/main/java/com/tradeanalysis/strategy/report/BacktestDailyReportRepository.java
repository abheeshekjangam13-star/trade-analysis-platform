package com.tradeanalysis.strategy.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestDailyReportRepository extends JpaRepository<BacktestDailyReport, Long> {
    List<BacktestDailyReport> findByBacktestResultIdOrderByTradeDate(Long backtestResultId);
}
