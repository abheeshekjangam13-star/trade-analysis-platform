package com.tradeanalysis.strategy.share;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StrategyShareRepository extends JpaRepository<StrategyShare, Long> {
    List<StrategyShare> findBySharedWithUserIdOrderByCreatedAtDesc(Long userId);
    List<StrategyShare> findByStrategyIdOrderByCreatedAtDesc(Long strategyId);
    Optional<StrategyShare> findFirstByStrategyIdAndSharedWithUserIdAndStatus(Long strategyId, Long userId, StrategyShare.Status status);
}
