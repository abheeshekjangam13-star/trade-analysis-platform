package com.tradeanalysis.strategy.controller;

import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.service.StrategyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/nifty-trades")
@Tag(name = "Nifty Trade Logs", description = "Query stored Nifty Opening Breakout trade records")
public class NiftyTradeLogController {

    private final StrategyService svc;

    public NiftyTradeLogController(StrategyService svc) { this.svc = svc; }

    @GetMapping
    @Operation(summary = "Get trade logs with a trade date between from and to (yyyy-MM-dd, both inclusive and optional; neither = all)")
    public List<NiftyTradeLog> between(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return svc.getNiftyTradeLogsBetween(from, to);
    }

    @GetMapping("/backtest/{backtestResultId}")
    @Operation(summary = "Get all trade logs for a specific backtest run")
    public List<NiftyTradeLog> byBacktest(@PathVariable Long backtestResultId) {
        return svc.getNiftyTradeLogs(backtestResultId);
    }

    @GetMapping("/strategy/{strategyId}")
    @Operation(summary = "Get all trade logs across all backtests for a strategy")
    public List<NiftyTradeLog> byStrategy(@PathVariable Long strategyId) {
        return svc.getNiftyTradeLogsByStrategy(strategyId);
    }

    @GetMapping("/date/{date}")
    @Operation(summary = "Get all trade logs for a specific trading date (yyyy-MM-dd)")
    public List<NiftyTradeLog> byDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return svc.getNiftyTradeLogsByDate(date);
    }
}
