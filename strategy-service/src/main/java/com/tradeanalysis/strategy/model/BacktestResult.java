package com.tradeanalysis.strategy.model;

import com.tradeanalysis.common.dto.Signal;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "backtest_result")
public class BacktestResult {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    private Strategy strategy;

    private String symbol;
    private Instant fromDate;
    private Instant toDate;
    private double initialCapital;
    private double finalCapital;
    private double totalReturn;   // percentage
    private double maxDrawdown;   // percentage
    private int    totalTrades;
    private int    winningTrades;
    private double sharpeRatio;

    private Integer lookbackDays;
    private Long runByUserId;               // user who ran the backtest (null before users existed)
    private Instant createdAt = Instant.now();

    @Transient
    private List<Signal> signals;  // not persisted – returned in the response

    protected BacktestResult() {}

    public BacktestResult(Strategy strategy, String symbol,
                          Instant fromDate, Instant toDate,
                          double initialCapital, double finalCapital,
                          double totalReturn, double maxDrawdown,
                          int totalTrades, int winningTrades,
                          double sharpeRatio, List<Signal> signals) {
        this.strategy = strategy; this.symbol = symbol;
        this.fromDate = fromDate; this.toDate = toDate;
        this.initialCapital = initialCapital; this.finalCapital = finalCapital;
        this.totalReturn = totalReturn; this.maxDrawdown = maxDrawdown;
        this.totalTrades = totalTrades; this.winningTrades = winningTrades;
        this.sharpeRatio = sharpeRatio; this.signals = signals;
    }

    public Long getId() { return id; }
    public Strategy getStrategy() { return strategy; }
    public String getSymbol() { return symbol; }
    public Instant getFromDate() { return fromDate; }
    public Instant getToDate() { return toDate; }
    public double getInitialCapital() { return initialCapital; }
    public double getFinalCapital() { return finalCapital; }
    public double getTotalReturn() { return totalReturn; }
    public double getMaxDrawdown() { return maxDrawdown; }
    public int getTotalTrades() { return totalTrades; }
    public int getWinningTrades() { return winningTrades; }
    public double getSharpeRatio() { return sharpeRatio; }
    public Integer getLookbackDays() { return lookbackDays; }
    public Long getRunByUserId() { return runByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setRunContext(int lookbackDays, Long runByUserId) {
        this.lookbackDays = lookbackDays; this.runByUserId = runByUserId;
    }
    public List<Signal> getSignals() { return signals; }
}
