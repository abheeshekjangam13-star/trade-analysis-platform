package com.tradeanalysis.strategy.trading;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StrategyLiveConfigRepository extends JpaRepository<StrategyLiveConfig, Long> {
    List<StrategyLiveConfig> findByModeNot(TradingMode mode);
    List<StrategyLiveConfig> findByUserId(Long userId);
    Optional<StrategyLiveConfig> findByStrategyIdAndUserId(Long strategyId, Long userId);
}
