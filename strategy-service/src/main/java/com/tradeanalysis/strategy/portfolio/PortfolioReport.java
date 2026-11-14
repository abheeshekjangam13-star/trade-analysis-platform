package com.tradeanalysis.strategy.portfolio;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Combined result of a portfolio's strategies over one look-back window. Drawdown is in rupees. */
@Entity
@Table(name = "portfolio_report")
public class PortfolioReport {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long portfolioId;
    private int windowDays;
    @Column(nullable = false, length = 32) private String symbol;
    @Column(nullable = false) private LocalDate fromDate;
    @Column(nullable = false) private LocalDate toDate;
    private double capital;
    private int trades, wins;
    private double grossPnl, charges, netPnl, endCapital, returnPct;
    private Double profitFactor;
    private double maxDrawdown;
    /** The portfolio's own capital fit: trades left out for lack of capital, and the capital the kept trades used. */
    private int skippedTrades;
    private Double peakCapitalUsed, avgCapitalUsed, maxCapitalUsed;
    private LocalDate peakCapitalOn;
    /** Capital that would have taken every trade at full lots (the most premium open at once); see PortfolioMath. */
    private Double capitalToFitAll;
    private Double maxDayLoss;     // the user's max day loss the report applied (null = none, see DayLossStop)
    private int dayLossStops;      // days on which it was reached
    private Boolean ivAssumed;
    private Long runByUserId;
    @Column(nullable = false) private Instant createdAt = Instant.now();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "portfolio_report_id", nullable = false)
    private List<PortfolioReportItem> items = new ArrayList<>();

    protected PortfolioReport() {}

    PortfolioReport(Long portfolioId, int windowDays, String symbol, LocalDate fromDate, LocalDate toDate,
                    double capital, PortfolioMath.Summary s, PortfolioMath.CapitalUse use, int skippedTrades,
                    Long runByUserId, List<PortfolioReportItem> items) {
        this.portfolioId = portfolioId; this.windowDays = windowDays; this.symbol = symbol;
        this.fromDate = fromDate; this.toDate = toDate; this.capital = capital;
        this.trades = s.trades(); this.wins = s.wins(); this.grossPnl = s.grossPnl(); this.charges = s.charges();
        this.netPnl = s.netPnl(); this.endCapital = capital + s.netPnl();
        this.returnPct = capital > 0 ? s.netPnl() / capital * 100 : 0;
        this.profitFactor = s.profitFactor(); this.maxDrawdown = s.maxDrawdown();
        this.skippedTrades = skippedTrades;
        this.avgCapitalUsed = use.avg(); this.maxCapitalUsed = use.max(); this.peakCapitalUsed = use.peak();
        this.peakCapitalOn = use.peakOn(); this.ivAssumed = use.peak() == null ? null : use.ivAssumed();
        this.runByUserId = runByUserId; this.items = new ArrayList<>(items);
    }

    public Long getId()                     { return id; }
    public Long getPortfolioId()            { return portfolioId; }
    public int getWindowDays()              { return windowDays; }
    public String getSymbol()               { return symbol; }
    public LocalDate getFromDate()          { return fromDate; }
    public LocalDate getToDate()            { return toDate; }
    public double getCapital()              { return capital; }
    public int getTrades()                  { return trades; }
    public int getWins()                    { return wins; }
    public double getGrossPnl()             { return grossPnl; }
    public double getCharges()              { return charges; }
    public double getNetPnl()               { return netPnl; }
    public double getEndCapital()           { return endCapital; }
    public double getReturnPct()            { return returnPct; }
    public Double getProfitFactor()         { return profitFactor; }
    public double getMaxDrawdown()          { return maxDrawdown; }
    public int getSkippedTrades()           { return skippedTrades; }
    public Double getPeakCapitalUsed()      { return peakCapitalUsed; }
    public Double getAvgCapitalUsed()       { return avgCapitalUsed; }
    public Double getMaxCapitalUsed()       { return maxCapitalUsed; }
    public LocalDate getPeakCapitalOn()     { return peakCapitalOn; }
    public Boolean getIvAssumed()           { return ivAssumed; }
    public Double getCapitalToFitAll()      { return capitalToFitAll; }
    void setCapitalToFitAll(Double amount)  { this.capitalToFitAll = amount; }
    public Double getMaxDayLoss()           { return maxDayLoss; }
    public int getDayLossStops()            { return dayLossStops; }
    void setDayLoss(Double limit, int stops) { this.maxDayLoss = limit; this.dayLossStops = stops; }
    public Long getRunByUserId()            { return runByUserId; }
    public Instant getCreatedAt()           { return createdAt; }
    public List<PortfolioReportItem> getItems() { return items; }
}
