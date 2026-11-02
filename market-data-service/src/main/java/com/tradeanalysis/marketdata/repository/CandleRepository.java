package com.tradeanalysis.marketdata.repository;

import com.tradeanalysis.marketdata.model.CandleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CandleRepository extends JpaRepository<CandleEntity, Long> {

    List<CandleEntity> findBySymbolAndIntervalCodeOrderByTimestampAsc(String symbol, String intervalCode);

    @Query("""
            SELECT c FROM CandleEntity c
            WHERE c.symbol = :symbol
              AND c.intervalCode = :interval
              AND c.timestamp BETWEEN :from AND :to
            ORDER BY c.timestamp ASC
            """)
    List<CandleEntity> findInRange(@Param("symbol") String symbol,
                                   @Param("interval") String interval,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to);

    @Query("SELECT DISTINCT c.symbol FROM CandleEntity c ORDER BY c.symbol")
    List<String> findDistinctSymbols();
}
