package com.tradeanalysis.strategy.engine;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** One simulated backtest day (see {@link BacktestDayCache}). */
@Entity
@Table(name = "backtest_day_cache")
public class BacktestDayCacheEntry {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "cache_key", nullable = false, length = 64)
    private String cacheKey;
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    protected BacktestDayCacheEntry() {}

    public BacktestDayCacheEntry(String cacheKey, LocalDate tradeDate, String payload, Instant now) {
        this.cacheKey = cacheKey; this.tradeDate = tradeDate; this.payload = payload; this.createdAt = now; this.lastUsedAt = now;
    }

    public String getCacheKey() { return cacheKey; }
    public LocalDate getTradeDate() { return tradeDate; }
    public String getPayload() { return payload; }
    public Instant getLastUsedAt() { return lastUsedAt; }
}
