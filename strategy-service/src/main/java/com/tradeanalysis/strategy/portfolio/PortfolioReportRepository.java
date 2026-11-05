package com.tradeanalysis.strategy.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PortfolioReportRepository extends JpaRepository<PortfolioReport, Long> {
    List<PortfolioReport> findByPortfolioIdOrderByCreatedAtDescWindowDaysAsc(Long portfolioId);
}
