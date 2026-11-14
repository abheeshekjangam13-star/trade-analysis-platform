package com.tradeanalysis.strategy.trading;

import jakarta.persistence.*;

import java.time.Instant;

/** Global trading switches and limits (single row). */
@Entity
@Table(name = "trading_control")
public class TradingControl {

    @Id private Short id = 1;
    private boolean killSwitch;
    private boolean liveEnabled;    // live trading on for the platform (an admin switches it; off = no new live orders)
    private boolean liveRestricted = true;   // on: only users with live access may trade live
    @Column(length = 300) private String liveRestrictedMessage;   // what everyone else sees (null = the default)
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

    /** Turns live trading on or off for everyone (ADMIN). */
    public void setLiveEnabled(boolean enabled, Long userId) {
        this.liveEnabled = enabled;
        this.updatedByUserId = userId;
        this.updatedAt = Instant.now();
    }

    /** Restricts live trading to users with live access (ADMIN); the others see the message. */
    public void setLiveRestriction(Boolean restricted, String message, Long userId) {
        if (restricted != null) this.liveRestricted = restricted;
        if (message != null) this.liveRestrictedMessage = message.isBlank() ? null : message.strip();
        this.updatedByUserId = userId;
        this.updatedAt = Instant.now();
    }

    public static final String DEFAULT_RESTRICTED_MESSAGE =
            "We are facing an issue with live trading and will resolve it soon. Paper trading works as usual.";

    public boolean isLiveRestricted()   { return liveRestricted; }
    /** The message users without live access see while live trading is restricted. */
    public String getLiveRestrictedMessage() { return liveRestrictedMessage == null ? DEFAULT_RESTRICTED_MESSAGE : liveRestrictedMessage; }
    public boolean isKillSwitch()       { return killSwitch; }
    public boolean isLiveEnabled()      { return liveEnabled; }
    public int getMaxOrdersPerDay()     { return maxOrdersPerDay; }
    public int getMaxQuantityPerOrder() { return maxQuantityPerOrder; }
    public double getDailyLossLimit()   { return dailyLossLimit; }
    public Long getUpdatedByUserId()    { return updatedByUserId; }
    public Instant getUpdatedAt()       { return updatedAt; }
}
