package com.tradeanalysis.strategy.trading;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** Every order the platform sends (paper or live), with its outcome. */
@Entity
@Table(name = "trade_order")
public class TradeOrder {

    public enum Status { OPEN, COMPLETE, REJECTED, CANCELLED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private LocalDate tradeDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) private TradingMode mode;
    @Column(nullable = false, length = 16) private String source;          // MANUAL / STRATEGY / KILL_SWITCH
    private Long strategyId;
    @Column(nullable = false, length = 8) private String exchange;
    @Column(nullable = false, length = 64) private String tradingsymbol;
    @Column(nullable = false, length = 4) private String transactionType;
    private int quantity;
    @Column(nullable = false, length = 8) private String product;
    @Column(nullable = false, length = 8) private String orderType;
    private Double price;
    private Double triggerPrice;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status = Status.OPEN;
    @Column(length = 64) private String brokerOrderId;
    private int filledQuantity;
    private Double averagePrice;
    @Column(length = 512) private String message;
    private Long userId;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected TradeOrder() {}

    public TradeOrder(LocalDate tradeDate, TradingMode mode, String source, Long strategyId, OrderRequest r, Long userId) {
        this.tradeDate = tradeDate; this.mode = mode; this.source = source; this.strategyId = strategyId;
        this.exchange = r.exchange(); this.tradingsymbol = r.tradingsymbol(); this.transactionType = r.transactionType();
        this.quantity = r.quantity(); this.product = r.product(); this.orderType = r.orderType();
        this.price = r.price(); this.triggerPrice = r.triggerPrice(); this.userId = userId;
    }

    void reject(String why)                  { status = Status.REJECTED; message = cut(why); touch(); }
    void placed(String brokerId, String msg) { brokerOrderId = brokerId; message = cut(msg); touch(); }
    void filled(int qty, Double avg, String msg) { status = Status.COMPLETE; filledQuantity = qty; averagePrice = avg; message = cut(msg); touch(); }
    void update(Status s, int filled, Double avg, String msg) {
        status = s; filledQuantity = filled; if (avg != null && avg > 0) averagePrice = avg; if (msg != null) message = cut(msg); touch();
    }
    private void touch() { updatedAt = Instant.now(); }
    private static String cut(String s) { return s == null || s.length() <= 512 ? s : s.substring(0, 512); }

    public Long getId()               { return id; }
    public LocalDate getTradeDate()   { return tradeDate; }
    public TradingMode getMode()      { return mode; }
    public String getSource()         { return source; }
    public Long getStrategyId()       { return strategyId; }
    public String getExchange()       { return exchange; }
    public String getTradingsymbol()  { return tradingsymbol; }
    public String getTransactionType(){ return transactionType; }
    public int getQuantity()          { return quantity; }
    public String getProduct()        { return product; }
    public String getOrderType()      { return orderType; }
    public Double getPrice()          { return price; }
    public Double getTriggerPrice()   { return triggerPrice; }
    public Status getStatus()         { return status; }
    public String getBrokerOrderId()  { return brokerOrderId; }
    public int getFilledQuantity()    { return filledQuantity; }
    public Double getAveragePrice()   { return averagePrice; }
    public String getMessage()        { return message; }
    public Long getUserId()           { return userId; }
    public Instant getCreatedAt()     { return createdAt; }
    public Instant getUpdatedAt()     { return updatedAt; }
}
