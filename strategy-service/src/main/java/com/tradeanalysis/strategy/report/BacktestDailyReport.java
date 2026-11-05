package com.tradeanalysis.strategy.report;

import jakarta.persistence.*;

import java.time.LocalDate;

/** One trading day of a backtest: traded or not, why not, and the day's P&L. */
@Entity
@Table(name = "backtest_daily_report")
public class BacktestDailyReport {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long backtestResultId;
    @Column(nullable = false) private LocalDate tradeDate;
    @Column(nullable = false, length = 3) private String dayOfWeek;
    private Double gapPercent;
    @Column(nullable = false, length = 16) private String status;   // TRADED / NO_TRADE / SKIPPED / NO_DATA
    private String reason;
    private int trades;
    private double netPnl;

    protected BacktestDailyReport() {}

    public BacktestDailyReport(Long backtestResultId, LocalDate tradeDate, Double gapPercent, String status,
                               String reason, int trades, double netPnl) {
        this.backtestResultId = backtestResultId; this.tradeDate = tradeDate;
        this.dayOfWeek = tradeDate.getDayOfWeek().name().substring(0, 3);
        this.gapPercent = gapPercent; this.status = status; this.reason = reason;
        this.trades = trades; this.netPnl = netPnl;
    }

    public Long getId()               { return id; }
    public Long getBacktestResultId() { return backtestResultId; }
    public LocalDate getTradeDate()   { return tradeDate; }
    public String getDayOfWeek()      { return dayOfWeek; }
    public Double getGapPercent()     { return gapPercent; }
    public String getStatus()         { return status; }
    public String getReason()         { return reason; }
    public int getTrades()            { return trades; }
    public double getNetPnl()         { return netPnl; }
}
