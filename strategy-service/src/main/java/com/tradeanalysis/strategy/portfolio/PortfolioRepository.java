package com.tradeanalysis.strategy.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {
    boolean existsByNameIgnoreCaseAndCreatedByUserId(String name, Long createdByUserId);
}
