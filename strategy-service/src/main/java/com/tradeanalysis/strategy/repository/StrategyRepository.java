package com.tradeanalysis.strategy.repository;

import com.tradeanalysis.strategy.model.Strategy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StrategyRepository extends JpaRepository<Strategy, Long> {
    Optional<Strategy> findByName(String name);
    boolean existsByNameAndCreatedByUserId(String name, Long createdByUserId);
}
