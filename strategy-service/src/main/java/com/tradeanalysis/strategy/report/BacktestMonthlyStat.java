package com.tradeanalysis.strategy.report;

import jakarta.persistence.*;

/** Month-wise statistics of a backtest's trades. Rupee amounts; drawdown is within the month. */
@Entity
@Table(name = "backtest_monthly_stat")
public class BacktestMonthlyStat {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long backtestResultId;
    @Column(nullable = false, length = 7) private String periodMonth;   // yyyy-MM
    private int trades, wins, losses;
    private double grossPnl, charges, netPnl, avgWin, avgLoss, bestTrade, worstTrade;
    private int targetHits, stopExits, failedBreakoutExits, eodExits;
    private double maxDrawdown, cumulativePnl;

    protected BacktestMonthlyStat() {}

    BacktestMonthlyStat(Long backtestResultId, String periodMonth, int trades, int wins, int losses,
                        double grossPnl, double charges, double netPnl, double avgWin, double avgLoss,
                        double bestTrade, double worstTrade, int targetHits, int stopExits,
                        int failedBreakoutExits, int eodExits, double maxDrawdown, double cumulativePnl) {
        this.backtestResultId = backtestResultId; this.periodMonth = periodMonth;
        this.trades = trades; this.wins = wins; this.losses = losses;
        this.grossPnl = grossPnl; this.charges = charges; this.netPnl = netPnl;
        this.avgWin = avgWin; this.avgLoss = avgLoss; this.bestTrade = bestTrade; this.worstTrade = worstTrade;
        this.targetHits = targetHits; this.stopExits = stopExits;
        this.failedBreakoutExits = failedBreakoutExits; this.eodExits = eodExits;
        this.maxDrawdown = maxDrawdown; this.cumulativePnl = cumulativePnl;
    }

    public Long getId()                 { return id; }
    public Long getBacktestResultId()   { return backtestResultId; }
    public String getPeriodMonth()      { return periodMonth; }
    public int getTrades()              { return trades; }
    public int getWins()                { return wins; }
    public int getLosses()              { return losses; }
    public double getGrossPnl()         { return grossPnl; }
    public double getCharges()          { return charges; }
    public double getNetPnl()           { return netPnl; }
    public double getAvgWin()           { return avgWin; }
    public double getAvgLoss()          { return avgLoss; }
    public double getBestTrade()        { return bestTrade; }
    public double getWorstTrade()       { return worstTrade; }
    public int getTargetHits()          { return targetHits; }
    public int getStopExits()           { return stopExits; }
    public int getFailedBreakoutExits() { return failedBreakoutExits; }
    public int getEodExits()            { return eodExits; }
    public double getMaxDrawdown()      { return maxDrawdown; }
    public double getCumulativePnl()    { return cumulativePnl; }
}
