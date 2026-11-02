package com.tradeanalysis.marketdata.controller;

import com.tradeanalysis.common.dto.Interval;
import com.tradeanalysis.common.dto.PriceSeries;
import com.tradeanalysis.marketdata.service.MarketDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/market-data")
@Tag(name = "Market Data", description = "Fetch OHLCV price series for any symbol")
public class MarketDataController {

    private final MarketDataService svc;

    public MarketDataController(MarketDataService svc) { this.svc = svc; }

    @GetMapping("/{symbol}")
    @Operation(summary = "Get latest candles for a symbol")
    public PriceSeries getSeries(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "DAY_1") Interval interval,
            @RequestParam(defaultValue = "200") int limit) {
        return svc.getSeries(symbol, interval, limit);
    }

    @GetMapping("/{symbol}/range")
    @Operation(summary = "Get candles in a time range")
    public PriceSeries getRange(
            @PathVariable String symbol,
            @RequestParam Interval interval,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return svc.getRange(symbol, interval, from, to);
    }

    @GetMapping("/symbols")
    @Operation(summary = "List all tracked symbols")
    public List<String> listSymbols() {
        return svc.listSymbols();
    }
}
