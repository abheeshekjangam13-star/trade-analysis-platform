package com.tradeanalysis.strategy.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "nifty_trade_log")
public class NiftyTradeLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long backtestResultId;
    private Long strategyId;
    private String symbol;

    private LocalDate tradeDate;
    private int tradeNumber;        // 1 or 2 within the day

    private String breakoutType;    // BULLISH / BEARISH
    private double referenceHigh;
    private double referenceLow;
    private Instant referenceTimestamp;

    private Instant entryTimestamp;
    private double entrySpotPrice;
    private String optionType;      // CE / PE
    private int strikePrice;
    private int lots;
    private int lotSize;

    private Double initialStopLoss; // reference-candle midpoint (nullable: rows predate this column)
    private Double finalStopLoss;   // trailing SL in force at exit

    private Double gapPercent;      // 09:15 open vs previous session close (nullable: rows predate this column)
    private Double charges;         // brokerage + statutory costs deducted from estimatedPnl

    private Instant exitTimestamp;
    private double exitSpotPrice;
    private String exitReason;      // SL_HIT / TARGET_HIT / EOD_SQUAREOFF

    // Indicative P&L using spot delta × lotSize × lots × 0.5 (ATM delta ≈ 0.5), minus charges
    private double estimatedPnl;

    private Instant createdAt = Instant.now();

    protected NiftyTradeLog() {}

    public NiftyTradeLog(Long strategyId, String symbol, LocalDate tradeDate, int tradeNumber,
                         String breakoutType, double referenceHigh, double referenceLow,
                         Instant referenceTimestamp, Instant entryTimestamp, double entrySpotPrice,
                         String optionType, int strikePrice, int lots, int lotSize, double initialStopLoss) {
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.tradeDate = tradeDate;
        this.tradeNumber = tradeNumber;
        this.breakoutType = breakoutType;
        this.referenceHigh = referenceHigh;
        this.referenceLow = referenceLow;
        this.referenceTimestamp = referenceTimestamp;
        this.entryTimestamp = entryTimestamp;
        this.entrySpotPrice = entrySpotPrice;
        this.optionType = optionType;
        this.strikePrice = strikePrice;
        this.lots = lots;
        this.lotSize = lotSize;
        this.initialStopLoss = initialStopLoss;
        this.finalStopLoss = initialStopLoss;
    }

    public void close(Instant exitTimestamp, double exitSpotPrice, String exitReason, double finalStopLoss) {
        this.finalStopLoss = finalStopLoss;
        this.exitTimestamp = exitTimestamp;
        this.exitSpotPrice = exitSpotPrice;
        this.exitReason = exitReason;
        double spotDelta = optionType.equals("CE")
                ? exitSpotPrice - entrySpotPrice
                : entrySpotPrice - exitSpotPrice;
        this.estimatedPnl = spotDelta * lotSize * lots * 0.5 - (charges == null ? 0 : charges);
    }

    /** Live use: record the stop currently in force on a still-open trade. */
    public void trackStop(double currentStop) { this.finalStopLoss = currentStop; }

    /** Call before {@link #close}: gap at the open and the round-trip charges for this trade. */
    public void setContext(Double gapPercent, double charges) {
        this.gapPercent = gapPercent;
        this.charges = charges;
    }

    public void setBacktestResultId(Long id) { this.backtestResultId = id; }

    public Long getId()                   { return id; }
    public Long getBacktestResultId()     { return backtestResultId; }
    public Long getStrategyId()           { return strategyId; }
    public String getSymbol()             { return symbol; }
    public LocalDate getTradeDate()       { return tradeDate; }
    public int getTradeNumber()           { return tradeNumber; }
    public String getBreakoutType()       { return breakoutType; }
    public double getReferenceHigh()      { return referenceHigh; }
    public double getReferenceLow()       { return referenceLow; }
    public Instant getReferenceTimestamp(){ return referenceTimestamp; }
    public Instant getEntryTimestamp()    { return entryTimestamp; }
    public double getEntrySpotPrice()     { return entrySpotPrice; }
    public String getOptionType()         { return optionType; }
    public int getStrikePrice()           { return strikePrice; }
    public int getLots()                  { return lots; }
    public int getLotSize()               { return lotSize; }
    public Double getGapPercent()         { return gapPercent; }
    public Double getCharges()            { return charges; }
    public Double getInitialStopLoss()    { return initialStopLoss; }
    public Double getFinalStopLoss()      { return finalStopLoss; }
    public Instant getExitTimestamp()     { return exitTimestamp; }
    public double getExitSpotPrice()      { return exitSpotPrice; }
    public String getExitReason()         { return exitReason; }
    public double getEstimatedPnl()       { return estimatedPnl; }
    public Instant getCreatedAt()         { return createdAt; }
}
