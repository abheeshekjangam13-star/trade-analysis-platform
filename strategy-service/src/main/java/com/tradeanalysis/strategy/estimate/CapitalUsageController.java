package com.tradeanalysis.strategy.estimate;

import com.tradeanalysis.strategy.model.NiftyTradeLog;
import com.tradeanalysis.strategy.repository.BacktestResultRepository;
import com.tradeanalysis.strategy.service.StrategyService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * How much of a backtest's capital its trades used: average and largest per trade (estimated option premium ×
 * quantity, see OptionEstimates), for the 30/90/180/360-day reports.
 */
@RestController
public class CapitalUsageController {

    public record Usage(Long backtestId, double capital, int trades, Double avgCapitalUsed, Double maxCapitalUsed,
                        Double avgPct, Double maxPct, boolean ivAssumed) {}

    static final int MAX_IDS = 50;

    private final StrategyService strategies;
    private final OptionEstimates estimates;
    private final BacktestResultRepository backtests;

    public CapitalUsageController(StrategyService strategies, OptionEstimates estimates, BacktestResultRepository backtests) {
        this.strategies = strategies; this.estimates = estimates; this.backtests = backtests;
    }

    /** A portfolio window: its strategies' trades together, against the portfolio capital. */
    public record Combined(int trades, double capital, Double avgCapitalUsed, Double maxCapitalUsed, Double peakCapitalUsed,
                           Double avgPct, Double maxPct, Double peakPct, java.time.LocalDate peakOn, boolean ivAssumed) {}

    /**
     * Capital used by several backtests' trades together (a portfolio report window): average and largest per trade,
     * and the peak in use at once, when trades of different strategies overlapped in time. Percentages are of
     * {@code capital} (the portfolio's).
     */
    @GetMapping("/api/backtests/capital-usage/combined")
    @Operation(summary = "Capital used by these backtests' trades together: per trade and the peak at once (yours; admins: any)")
    public Combined combined(@RequestParam List<Long> ids, @RequestParam double capital) {
        if (ids.size() > MAX_IDS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_IDS + " backtests at a time");
        List<NiftyTradeLog> trades = new ArrayList<>();
        Map<Long, Double> capitals = new HashMap<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            // checks the caller may see it (else 404); trades a portfolio left out for capital used none
            strategies.getNiftyTradeLogs(id).stream().filter(t -> t.getSkipReason() == null).forEach(trades::add);
            capitals.put(id, capital);
        }
        estimates.estimate(trades, capitals);
        List<NiftyTradeLog> used = trades.stream().filter(t -> t.getCapitalUsed() != null).toList();
        if (used.isEmpty()) return new Combined(0, capital, null, null, null, null, null, null, null, false);
        DoubleSummaryStatistics s = used.stream().mapToDouble(NiftyTradeLog::getCapitalUsed).summaryStatistics();

        // Peak at once: walk entries (+) and exits (−) in time order; an exit at the same instant frees capital first.
        record Event(java.time.Instant at, double delta, java.time.LocalDate day) {}
        List<Event> events = new ArrayList<>();
        for (NiftyTradeLog t : used) {
            java.time.Instant in = t.getEntryTimestamp() != null ? t.getEntryTimestamp()
                    : t.getTradeDate().atTime(9, 20).atZone(OptionEstimates.IST).toInstant();
            java.time.Instant out = t.getExitTimestamp() != null ? t.getExitTimestamp() : in.plusSeconds(60);
            events.add(new Event(in, t.getCapitalUsed(), t.getTradeDate()));
            events.add(new Event(out, -t.getCapitalUsed(), t.getTradeDate()));
        }
        events.sort(Comparator.comparing(Event::at).thenComparingDouble(Event::delta));
        double open = 0, peak = 0; java.time.LocalDate peakOn = null;
        for (Event e : events) { open += e.delta(); if (open > peak + 1e-9) { peak = open; peakOn = e.day(); } }

        double avg = Math.round(s.getAverage());
        java.util.function.DoubleFunction<Double> share = v -> capital > 0 ? Math.round(v / capital * 1000) / 10.0 : null;
        return new Combined(used.size(), capital, avg, s.getMax(), (double) Math.round(peak), share.apply(avg), share.apply(s.getMax()),
                share.apply(peak), peakOn, used.stream().anyMatch(t -> Boolean.TRUE.equals(t.getIvAssumed())));
    }

    @GetMapping("/api/backtests/capital-usage")
    @Operation(summary = "Average and largest capital used per trade for these backtests (yours; admins: any)")
    public List<Usage> usage(@RequestParam List<Long> ids) {
        if (ids.size() > MAX_IDS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_IDS + " backtests at a time");
        List<Usage> out = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            List<NiftyTradeLog> trades = strategies.getNiftyTradeLogs(id).stream()   // checks the caller may see it (else 404)
                    .filter(t -> t.getSkipReason() == null).toList();                   // left out for capital: none used
            double capital = backtests.findById(id).map(b -> b.getInitialCapital()).orElse(0.0);
            estimates.estimate(trades, Map.of(id, capital));
            DoubleSummaryStatistics s = trades.stream().filter(t -> t.getCapitalUsed() != null)
                    .mapToDouble(NiftyTradeLog::getCapitalUsed).summaryStatistics();
            boolean any = s.getCount() > 0;
            Double avg = any ? (double) Math.round(s.getAverage()) : null, max = any ? s.getMax() : null;
            out.add(new Usage(id, capital, trades.size(), avg, max,
                    any && capital > 0 ? Math.round(avg / capital * 1000) / 10.0 : null,
                    any && capital > 0 ? Math.round(max / capital * 1000) / 10.0 : null,
                    trades.stream().anyMatch(t -> Boolean.TRUE.equals(t.getIvAssumed()))));
        }
        return out;
    }
}
