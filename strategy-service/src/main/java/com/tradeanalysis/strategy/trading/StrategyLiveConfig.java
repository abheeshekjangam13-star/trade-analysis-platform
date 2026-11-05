package com.tradeanalysis.strategy.trading;

import jakarta.persistence.*;

import java.time.Instant;

/** Whether the live runner trades a strategy for one user, and how (OFF / PAPER / LIVE). Each user has their own. */
@Entity
@Table(name = "strategy_user_mode")
public class StrategyLiveConfig {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long strategyId;
    @Column(nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) private TradingMode mode = TradingMode.OFF;
    @Column(nullable = false, length = 8) private String product = "MIS";
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected StrategyLiveConfig() {}

    public StrategyLiveConfig(Long strategyId, Long userId) { this.strategyId = strategyId; this.userId = userId; }

    public void set(TradingMode mode, String product) {
        this.mode = mode;
        if (product != null) this.product = product;
        this.updatedAt = Instant.now();
    }

    public Long getId()              { return id; }
    public Long getStrategyId()      { return strategyId; }
    public Long getUserId()          { return userId; }
    public TradingMode getMode()     { return mode; }
    public String getProduct()       { return product; }
    public Instant getUpdatedAt()    { return updatedAt; }
}
