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
    private int lots;               // lots taken (after the capital limit, see CapitalFit)
    private int requestedLots;      // lots the strategy asked for, before any capital limit
    private int lotSize;

    private Double initialStopLoss; // reference-candle midpoint (nullable: rows predate this column)
    private Double finalStopLoss;   // trailing SL in force at exit

    private Double gapPercent;      // 09:15 open vs previous session close (nullable: rows predate this column)
    private Double charges;         // brokerage + statutory costs deducted from estimatedPnl

    private Instant exitTimestamp;
    private double exitSpotPrice;
    private String exitReason;      // SL_HIT / TARGET_HIT / EOD_SQUAREOFF
    private String skipReason;      // null = taken; CAPITAL = a portfolio report left it out (no capital free at entry)

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
        this.requestedLots = lots;
        this.lotSize = lotSize;
        this.initialStopLoss = initialStopLoss;
        this.finalStopLoss = initialStopLoss;
    }

    public void close(Instant exitTimestamp, double exitSpotPrice, String exitReason, double finalStopLoss) {
        this.finalStopLoss = finalStopLoss;
        this.exitTimestamp = exitTimestamp;
        this.exitSpotPrice = exitSpotPrice;
        this.exitReason = exitReason;
        this.estimatedPnl = pnlFor(lots);
    }

    /**
     * Sets the lots this trade is taken with, for backtests that must fit their capital (see CapitalFit), and
     * restates its estimated P&L for them. Only for a closed trade.
     */
    public void resize(int newLots) {
        int oldLots = this.lots;
        this.lots = newLots;
        if (exitTimestamp != null) this.estimatedPnl = pnlFor(newLots);
        // The capital shown for the trade follows its lots (estimated() ran before the resize).
        if (estEntryPremium != null && oldLots > 0) {
            this.capitalUsed = (double) Math.round(estEntryPremium * newLots * lotSize);
            if (capitalUsedPct != null) this.capitalUsedPct = Math.round(capitalUsedPct * newLots / oldLots * 10) / 10.0;
        }
    }

    /** What the trade would make if it closed at this index price now: the same estimate as its P&L (charges included). */
    public double pnlAt(double spot) {
        double spotDelta = optionType.equals("CE") ? spot - entrySpotPrice : entrySpotPrice - spot;
        return spotDelta * lotSize * lots * 0.5 - (charges == null ? 0 : charges);
    }

    /** Indicative P&L for a number of lots: spot move × lot size × lots × 0.5 (ATM delta), minus charges. */
    private double pnlFor(int n) {
        double spotDelta = optionType.equals("CE")
                ? exitSpotPrice - entrySpotPrice
                : entrySpotPrice - exitSpotPrice;
        return spotDelta * lotSize * n * 0.5 - (charges == null ? 0 : charges);
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

    // ── Estimates for display (not stored; see OptionEstimates) ──────────────
    @Transient private Double estEntryPremium;   // option premium per unit at entry (Black-Scholes, India VIX)
    @Transient private Double capitalUsed;       // estEntryPremium × lots × lotSize
    @Transient private Double capitalUsedPct;    // of the backtest's capital
    @Transient private Double estIv;             // volatility used (India VIX / 100)
    @Transient private Boolean ivAssumed;        // true when India VIX wasn't available
    @Transient private Double backtestCapital;   // capital the trade's backtest ran with (lots were sized to it)

    public void estimated(double premium, Double capital, double iv, boolean assumed) {
        this.estEntryPremium = Math.round(premium * 100) / 100.0;
        this.capitalUsed = (double) Math.round(premium * lots * lotSize);
        this.capitalUsedPct = capital == null || capital <= 0 ? null : Math.round(capitalUsed / capital * 1000) / 10.0;
        this.estIv = Math.round(iv * 1000) / 1000.0;
        this.ivAssumed = assumed;
    }

    public int getRequestedLots()         { return requestedLots; }
    public Double getEstEntryPremium()    { return estEntryPremium; }
    public Double getCapitalUsed()        { return capitalUsed; }
    public Double getCapitalUsedPct()     { return capitalUsedPct; }
    public Double getEstIv()              { return estIv; }
    public Boolean getIvAssumed()         { return ivAssumed; }
    public Double getBacktestCapital()    { return backtestCapital; }
    public String getSkipReason()         { return skipReason; }
    /** Marks the trade as left out (e.g. CAPITAL: not even one lot was free at its entry); its P&L is what it would have made. */
    public void markSkipped(String reason) { this.skipReason = reason; }
    public void setBacktestCapital(Double capital) { this.backtestCapital = capital; }
}
