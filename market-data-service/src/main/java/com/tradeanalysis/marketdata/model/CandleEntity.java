package com.tradeanalysis.marketdata.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "candle",
       uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "interval_code", "timestamp"}),
       indexes = {
           @Index(name = "idx_candle_symbol_interval", columnList = "symbol, interval_code"),
           @Index(name = "idx_candle_timestamp", columnList = "timestamp")
       })
public class CandleEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "interval_code", nullable = false, length = 10)
    private String intervalCode;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(precision = 20, scale = 8) private BigDecimal open;
    @Column(precision = 20, scale = 8) private BigDecimal high;
    @Column(precision = 20, scale = 8) private BigDecimal low;
    @Column(precision = 20, scale = 8) private BigDecimal close;
    private long volume;

    protected CandleEntity() {}

    public CandleEntity(String symbol, String intervalCode, Instant timestamp,
                        BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        this.symbol = symbol; this.intervalCode = intervalCode; this.timestamp = timestamp;
        this.open = open; this.high = high; this.low = low; this.close = close; this.volume = volume;
    }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public String getIntervalCode() { return intervalCode; }
    public Instant getTimestamp() { return timestamp; }
    public BigDecimal getOpen() { return open; }
    public BigDecimal getHigh() { return high; }
    public BigDecimal getLow() { return low; }
    public BigDecimal getClose() { return close; }
    public long getVolume() { return volume; }
}
