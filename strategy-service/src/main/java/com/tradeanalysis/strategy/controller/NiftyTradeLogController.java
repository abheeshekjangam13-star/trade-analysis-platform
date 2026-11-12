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
    private final com.tradeanalysis.strategy.estimate.OptionEstimates estimates;
    private final com.tradeanalysis.strategy.repository.BacktestResultRepository backtests;

    public NiftyTradeLogController(StrategyService svc, com.tradeanalysis.strategy.estimate.OptionEstimates estimates,
                                   com.tradeanalysis.strategy.repository.BacktestResultRepository backtests) {
        this.svc = svc; this.estimates = estimates; this.backtests = backtests;
    }

    /**
     * Adds each trade's backtest capital, estimated option premium and capital used (vs that capital); see
     * OptionEstimates. The same trade is stored once per backtest, and backtests run with different capitals size it
     * with different lots, so callers need the capital to tell those copies apart.
     */
    private List<NiftyTradeLog> withCapital(List<NiftyTradeLog> trades) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (NiftyTradeLog t : trades) if (t.getBacktestResultId() != null) ids.add(t.getBacktestResultId());
        java.util.Map<Long, Double> capital = new java.util.HashMap<>();
        backtests.findAllById(ids).forEach(b -> capital.put(b.getId(), b.getInitialCapital()));
        for (NiftyTradeLog t : trades) if (t.getBacktestResultId() != null) t.setBacktestCapital(capital.get(t.getBacktestResultId()));
        estimates.estimate(trades, capital);
        return trades;
    }

    @GetMapping
    @Operation(summary = "Get trade logs with a trade date between from and to (yyyy-MM-dd, both inclusive and optional; neither = all)")
    public List<NiftyTradeLog> between(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return withCapital(svc.getNiftyTradeLogsBetween(from, to));
    }

    @GetMapping("/backtest/{backtestResultId}")
    @Operation(summary = "Get all trade logs for a specific backtest run")
    public List<NiftyTradeLog> byBacktest(@PathVariable Long backtestResultId) {
        return withCapital(svc.getNiftyTradeLogs(backtestResultId));
    }

    @GetMapping("/strategy/{strategyId}")
    @Operation(summary = "Get all trade logs across all backtests for a strategy")
    public List<NiftyTradeLog> byStrategy(@PathVariable Long strategyId) {
        return withCapital(svc.getNiftyTradeLogsByStrategy(strategyId));
    }

    @GetMapping("/date/{date}")
    @Operation(summary = "Get all trade logs for a specific trading date (yyyy-MM-dd)")
    public List<NiftyTradeLog> byDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return withCapital(svc.getNiftyTradeLogsByDate(date));
    }
}
