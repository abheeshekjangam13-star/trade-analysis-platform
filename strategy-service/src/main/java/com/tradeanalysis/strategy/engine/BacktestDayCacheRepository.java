package com.tradeanalysis.strategy.engine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface BacktestDayCacheRepository extends JpaRepository<BacktestDayCacheEntry, Long> {

    List<BacktestDayCacheEntry> findByCacheKeyAndTradeDateBetween(String cacheKey, LocalDate from, LocalDate to);

    @Modifying @Transactional
    @Query("update BacktestDayCacheEntry e set e.lastUsedAt = :now where e.cacheKey = :key")
    int touch(@Param("key") String key, @Param("now") Instant now);

    @Modifying @Transactional
    @Query("delete from BacktestDayCacheEntry e where e.lastUsedAt < :before")
    int deleteUnusedSince(@Param("before") Instant before);
}
