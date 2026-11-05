package com.tradeanalysis.strategy.trading;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LivePositionRepository extends JpaRepository<LivePosition, Long> {
    Optional<LivePosition> findByStrategyIdAndUserIdAndModeAndEntryCandle(Long strategyId, Long userId, TradingMode mode, Instant entryCandle);
    List<LivePosition> findByTradeDateOrderByOpenedAtDesc(LocalDate date);
    List<LivePosition> findByTradeDateAndUserIdOrderByOpenedAtDesc(LocalDate date, Long userId);
    List<LivePosition> findByStatus(LivePosition.Status status);
    List<LivePosition> findByTradeDateAndModeAndStatus(LocalDate date, TradingMode mode, LivePosition.Status status);
}
