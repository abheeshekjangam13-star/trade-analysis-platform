package com.tradeanalysis.strategy.trading;

import jakarta.persistence.*;

import java.time.Instant;

/** Global trading switches and limits (single row). */
@Entity
@Table(name = "trading_control")
public class TradingControl {

    @Id private Short id = 1;
    private boolean killSwitch;
    private int maxOrdersPerDay;
    private int maxQuantityPerOrder;
    private double dailyLossLimit;
    private Long updatedByUserId;
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected TradingControl() {}

    public void update(Boolean killSwitch, Integer maxOrdersPerDay, Integer maxQuantityPerOrder, Double dailyLossLimit, Long userId) {
        if (killSwitch != null) this.killSwitch = killSwitch;
        if (maxOrdersPerDay != null) this.maxOrdersPerDay = maxOrdersPerDay;
        if (maxQuantityPerOrder != null) this.maxQuantityPerOrder = maxQuantityPerOrder;
        if (dailyLossLimit != null) this.dailyLossLimit = dailyLossLimit;
        this.updatedByUserId = userId;
        this.updatedAt = Instant.now();
    }

    public boolean isKillSwitch()       { return killSwitch; }
    public int getMaxOrdersPerDay()     { return maxOrdersPerDay; }
    public int getMaxQuantityPerOrder() { return maxQuantityPerOrder; }
    public double getDailyLossLimit()   { return dailyLossLimit; }
    public Long getUpdatedByUserId()    { return updatedByUserId; }
    public Instant getUpdatedAt()       { return updatedAt; }
}
