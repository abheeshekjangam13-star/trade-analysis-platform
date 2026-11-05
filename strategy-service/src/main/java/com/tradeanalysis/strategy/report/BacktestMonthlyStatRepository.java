package com.tradeanalysis.strategy.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestMonthlyStatRepository extends JpaRepository<BacktestMonthlyStat, Long> {
    List<BacktestMonthlyStat> findByBacktestResultIdOrderByPeriodMonth(Long backtestResultId);
}
