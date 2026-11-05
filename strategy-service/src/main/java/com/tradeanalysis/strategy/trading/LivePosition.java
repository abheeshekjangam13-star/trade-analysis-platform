package com.tradeanalysis.strategy.trading;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** A position the live runner opened for a strategy (paper or live), from entry to exit. */
@Entity
@Table(name = "live_position")
public class LivePosition {

    public enum Status { OPEN, CLOSED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long strategyId;
    private Long userId;          // whose position (each user trades their own copy of a strategy)
    @Column(nullable = false) private LocalDate tradeDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) private TradingMode mode;
    @Column(nullable = false, length = 2) private String optionType;
    @Column(length = 64) private String tradingsymbol;
    private int quantity;
    @Column(nullable = false) private Instant entryCandle;
    private double entrySpot;
    private Double currentStop;
    private Long entryOrderId;
    private Double entryPrice;
    private Long exitOrderId;
    private Double exitPrice;
    private Double exitSpot;
    @Column(length = 32) private String exitReason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) private Status status = Status.OPEN;
    private Double pnl;
    @Column(length = 512) private String message;
    @Column(nullable = false) private Instant openedAt = Instant.now();
    private Instant closedAt;

    protected LivePosition() {}

    public LivePosition(Long strategyId, Long userId, LocalDate tradeDate, TradingMode mode, String optionType, String tradingsymbol,
                        int quantity, Instant entryCandle, double entrySpot, Double currentStop) {
        this.userId = userId;
        this.strategyId = strategyId; this.tradeDate = tradeDate; this.mode = mode; this.optionType = optionType;
        this.tradingsymbol = tradingsymbol; this.quantity = quantity; this.entryCandle = entryCandle;
        this.entrySpot = entrySpot; this.currentStop = currentStop;
    }

    void entered(TradeOrder order) {
        entryOrderId = order.getId(); entryPrice = order.getAveragePrice();
        if (order.getStatus() == TradeOrder.Status.REJECTED) { status = Status.FAILED; message = order.getMessage(); closedAt = Instant.now(); }
    }
    void failed(String why)                  { status = Status.FAILED; message = why; closedAt = Instant.now(); }
    void trackStop(Double stop)              { currentStop = stop; }
    void entryPrice(Double price)            { if (price != null && price > 0) entryPrice = price; }
    void closed(TradeOrder exit, Double exitSpot, String reason, Double pnl, String msg) {
        exitOrderId = exit == null ? null : exit.getId();
        exitPrice = exit == null ? null : exit.getAveragePrice();
        this.exitSpot = exitSpot; this.exitReason = reason; this.pnl = pnl; this.message = msg;
        status = Status.CLOSED; closedAt = Instant.now();
    }

    public Long getId()             { return id; }
    public Long getUserId()          { return userId; }
    public Long getStrategyId()     { return strategyId; }
    public LocalDate getTradeDate() { return tradeDate; }
    public TradingMode getMode()    { return mode; }
    public String getOptionType()   { return optionType; }
    public String getTradingsymbol(){ return tradingsymbol; }
    public int getQuantity()        { return quantity; }
    public Instant getEntryCandle() { return entryCandle; }
    public double getEntrySpot()    { return entrySpot; }
    public Double getCurrentStop()  { return currentStop; }
    public Long getEntryOrderId()   { return entryOrderId; }
    public Double getEntryPrice()   { return entryPrice; }
    public Long getExitOrderId()    { return exitOrderId; }
    public Double getExitPrice()    { return exitPrice; }
    public Double getExitSpot()     { return exitSpot; }
    public String getExitReason()   { return exitReason; }
    public Status getStatus()       { return status; }
    public Double getPnl()          { return pnl; }
    public String getMessage()      { return message; }
    public Instant getOpenedAt()    { return openedAt; }
    public Instant getClosedAt()    { return closedAt; }
}
