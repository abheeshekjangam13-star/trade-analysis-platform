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

    // P&L minus charges: (exitPremium − entryPremium) × lotSize × lots when the trade has real option prices
    // (see OptionPrices), else the estimate spot move × lotSize × lots × 0.5 (ATM delta ≈ 0.5)
    private double estimatedPnl;

    // Real option prices (null = estimated): the contract live trading would have bought, its premium when the entry
    // was taken (the signal candle's close) and when the exit was (the end of the exit minute)
    private String optionSymbol;
    private Double entryPremium;
    private Double exitPremium;

    /** The contract's 1-minute closes by minute start, while a backtest prices the trade (not stored, not sent). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private transient java.util.NavigableMap<Instant, Double> premiumCloses;

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
        if (premiumCloses != null) this.exitPremium = premiumAt(exitPricedAt());
        this.estimatedPnl = pnlFor(lots);
    }

    /**
     * Prices the trade with its option's real 1-minute candles: the entry premium at {@code enteredAt} (when the signal
     * candle closed and live trading would buy) and, once closed, the exit premium. Without a premium at the entry the
     * trade stays estimated.
     */
    public void priceWith(String optionSymbol, java.util.NavigableMap<Instant, Double> closes, Instant enteredAt) {
        Double entry = premiumBefore(closes, enteredAt);
        if (entry == null) return;
        this.optionSymbol = optionSymbol;
        this.premiumCloses = closes;
        this.entryPremium = entry;
        this.exitPremium = exitTimestamp == null ? null : premiumAt(exitPricedAt());
        if (exitTimestamp != null) this.estimatedPnl = pnlFor(lots);
    }

    /**
     * When the exit is priced. An engine stamps an exit with the 1-minute candle the stop or target was hit in and live
     * trading sells as that minute ends; the max day loss closes trades at the moment it is checked (a minute's end).
     */
    private Instant exitPricedAt() {
        return "MAX_DAY_LOSS".equals(exitReason) ? exitTimestamp : exitTimestamp.plusSeconds(60);
    }

    /** The option's price at {@code at}: the close of the latest minute ending by then (null without real prices). */
    public Double premiumAt(Instant at) {
        return premiumCloses == null ? null : premiumBefore(premiumCloses, at);
    }

    /** The close of the latest minute that ended by {@code at}, if it ended at most 5 minutes before; null if none. */
    static Double premiumBefore(java.util.NavigableMap<Instant, Double> closes, Instant at) {
        if (closes == null || at == null) return null;
        java.util.Map.Entry<Instant, Double> e = closes.lowerEntry(at.minusSeconds(59));
        return e == null || e.getKey().isBefore(at.minusSeconds(6 * 60)) ? null : e.getValue();
    }

    /** True when the P&L comes from the option's real prices, not the estimate. */
    public boolean hasRealPrices() { return entryPremium != null && exitPremium != null; }

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

    /**
     * What the trade would make if it closed at {@code at}: at the option's real price then when the trade has real
     * prices, else the estimate at this index price (charges included either way).
     */
    public double pnlAt(Instant at, double spot) {
        Double premium = entryPremium == null ? null : premiumAt(at);
        if (premium == null) return pnlAt(spot);
        return (premium - entryPremium) * lotSize * lots - (charges == null ? 0 : charges);
    }

    /**
     * P&L for a number of lots, minus charges: the real premium move × lot size × lots when the trade has real prices,
     * else the estimate spot move × lot size × lots × 0.5 (ATM delta).
     */
    private double pnlFor(int n) {
        if (hasRealPrices()) return (exitPremium - entryPremium) * lotSize * n - (charges == null ? 0 : charges);
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
    public String getOptionSymbol()       { return optionSymbol; }
    public Double getEntryPremium()       { return entryPremium; }
    public Double getExitPremium()        { return exitPremium; }
    public Instant getCreatedAt()         { return createdAt; }

    // ── Estimates for display (not stored; see OptionEstimates) ──────────────
    @Transient private Double estEntryPremium;   // option premium per unit at entry (the real one when known, else Black-Scholes, India VIX)
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
