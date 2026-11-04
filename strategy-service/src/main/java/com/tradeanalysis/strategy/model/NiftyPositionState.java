package com.tradeanalysis.strategy.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Durable state of the live/paper Opening-Breakout position, written on every change so a restart
 * or WebSocket reconnect resumes with the latest trailing stop (never the initial one).
 */
@Entity
@Table(name = "nifty_position_state")
public class NiftyPositionState {

    public enum Status { OPEN, EXIT_PENDING, CLOSED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    private Long strategyId;
    private LocalDate tradeDate;
    private int dailyTradeCount;          // entries taken today, including this one

    private String direction;             // BULLISH / BEARISH
    private String tradingSymbol;         // option instrument, e.g. NIFTY25NOV23700CE
    private int quantity;

    private double referenceHigh;
    private double referenceLow;
    private double initialStopLoss;
    private double currentStopLoss;
    private Instant lastProcessedCandle;  // start time of the last 1-min candle applied to the stop

    private String entryOrderId;
    private String entryOrderStatus;
    private String exitOrderId;
    private String exitOrderStatus;
    private int exitOrderQuantity;        // quantity of the current exit order (remainder after earlier fills)
    private int exitFilledQuantity;       // total exited across all exit orders

    @Enumerated(EnumType.STRING)
    private Status status;

    private Instant updatedAt;

    protected NiftyPositionState() {}

    public NiftyPositionState(Long strategyId, LocalDate tradeDate, int dailyTradeCount, String direction,
                              String tradingSymbol, int quantity, double referenceHigh, double referenceLow,
                              double initialStopLoss, Instant entryCandle,
                              String entryOrderId, String entryOrderStatus) {
        this.strategyId = strategyId;
        this.tradeDate = tradeDate;
        this.dailyTradeCount = dailyTradeCount;
        this.direction = direction;
        this.tradingSymbol = tradingSymbol;
        this.quantity = quantity;
        this.referenceHigh = referenceHigh;
        this.referenceLow = referenceLow;
        this.initialStopLoss = initialStopLoss;
        this.currentStopLoss = initialStopLoss;
        this.lastProcessedCandle = entryCandle;
        this.entryOrderId = entryOrderId;
        this.entryOrderStatus = entryOrderStatus;
        this.status = Status.OPEN;
    }

    @PrePersist @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public void updateStop(double stop, Instant candle) { currentStopLoss = stop; lastProcessedCandle = candle; }
    public void exitRequested()                       { status = Status.EXIT_PENDING; }
    public void exitPlaced(String orderId, int qty)   { exitOrderId = orderId; exitOrderQuantity = qty; exitOrderStatus = "PLACED"; }
    public void exitProgress(String orderStatus, int filled) { exitOrderStatus = orderStatus; exitFilledQuantity = filled; }
    public void exitFailed(String orderStatus)        { exitOrderStatus = orderStatus; exitOrderId = null; status = Status.OPEN; }
    public void closed()                              { status = Status.CLOSED; }

    public Long getId()                    { return id; }
    public Long getStrategyId()            { return strategyId; }
    public LocalDate getTradeDate()        { return tradeDate; }
    public int getDailyTradeCount()        { return dailyTradeCount; }
    public String getDirection()           { return direction; }
    public String getTradingSymbol()       { return tradingSymbol; }
    public int getQuantity()               { return quantity; }
    public double getReferenceHigh()       { return referenceHigh; }
    public double getReferenceLow()        { return referenceLow; }
    public double getInitialStopLoss()     { return initialStopLoss; }
    public double getCurrentStopLoss()     { return currentStopLoss; }
    public Instant getLastProcessedCandle(){ return lastProcessedCandle; }
    public String getEntryOrderId()        { return entryOrderId; }
    public String getEntryOrderStatus()    { return entryOrderStatus; }
    public String getExitOrderId()         { return exitOrderId; }
    public String getExitOrderStatus()     { return exitOrderStatus; }
    public int getExitOrderQuantity()      { return exitOrderQuantity; }
    public int getExitFilledQuantity()     { return exitFilledQuantity; }
    public Status getStatus()              { return status; }
    public Instant getUpdatedAt()          { return updatedAt; }
}
