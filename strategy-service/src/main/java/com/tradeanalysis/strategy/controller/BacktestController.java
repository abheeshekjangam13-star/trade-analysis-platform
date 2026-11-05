package com.tradeanalysis.strategy.controller;

import com.tradeanalysis.strategy.report.BacktestDailyReport;
import com.tradeanalysis.strategy.report.BacktestMonthlyStat;

import com.tradeanalysis.strategy.model.BacktestResult;
import com.tradeanalysis.strategy.service.StrategyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/backtests")
@Tag(name = "Backtests", description = "Run and retrieve back-test results")
public class BacktestController {

    private final StrategyService svc;

    public BacktestController(StrategyService svc) { this.svc = svc; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Run a back-test for a strategy against a symbol")
    public BacktestResult run(@RequestParam Long strategyId,
                              @RequestParam String symbol,
                              @RequestParam(defaultValue = "365") int lookbackDays,
                              @RequestParam(defaultValue = "100000") double capital) {
        return svc.backtest(strategyId, symbol, lookbackDays, capital);
    }

    @GetMapping("/{strategyId}")
    @Operation(summary = "Get all back-test results for a strategy")
    public List<BacktestResult> list(@PathVariable Long strategyId) {
        return svc.getBacktests(strategyId);
    }

    @GetMapping("/{backtestId}/daily")
    @Operation(summary = "Stored day-by-day report of a backtest (traded or not, why not, P&L)")
    public List<BacktestDailyReport> daily(@PathVariable Long backtestId) {
        return svc.getDailyReport(backtestId);
    }

    @GetMapping("/{backtestId}/monthly")
    @Operation(summary = "Stored month-wise statistics of a backtest")
    public List<BacktestMonthlyStat> monthly(@PathVariable Long backtestId) {
        return svc.getMonthlyStats(backtestId);
    }
}
