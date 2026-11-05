package com.tradeanalysis.strategy.portfolio;

import jakarta.persistence.*;

/** One strategy's part of a portfolio report, with the backtest it came from. */
@Entity
@Table(name = "portfolio_report_item")
public class PortfolioReportItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long strategyId;
    @Column(nullable = false) private Long backtestResultId;
    private int trades, wins;
    private double netPnl, maxDrawdown;

    protected PortfolioReportItem() {}

    PortfolioReportItem(Long strategyId, Long backtestResultId, PortfolioMath.Summary s) {
        this.strategyId = strategyId; this.backtestResultId = backtestResultId;
        this.trades = s.trades(); this.wins = s.wins(); this.netPnl = s.netPnl(); this.maxDrawdown = s.maxDrawdown();
    }

    public Long getId()               { return id; }
    public Long getStrategyId()       { return strategyId; }
    public Long getBacktestResultId() { return backtestResultId; }
    public int getTrades()            { return trades; }
    public int getWins()              { return wins; }
    public double getNetPnl()         { return netPnl; }
    public double getMaxDrawdown()    { return maxDrawdown; }
}
