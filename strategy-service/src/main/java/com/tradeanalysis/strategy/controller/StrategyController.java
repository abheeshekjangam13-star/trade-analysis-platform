package com.tradeanalysis.strategy.controller;

import com.tradeanalysis.strategy.model.BacktestResult;
import com.tradeanalysis.strategy.model.Strategy;
import com.tradeanalysis.strategy.service.StrategyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/strategies")
@Tag(name = "Strategies", description = "Create and manage trading strategies")
public class StrategyController {

    private final StrategyService svc;
    private final com.tradeanalysis.strategy.describe.StrategyDescriptionParser parser;

    public StrategyController(StrategyService svc, com.tradeanalysis.strategy.describe.StrategyDescriptionParser parser) {
        this.svc = svc; this.parser = parser;
    }

    public record DescribeRequest(String description) {}

    @PostMapping("/parse")
    @Operation(summary = "Read strategy type and parameters from a plain-English description (nothing is saved); "
            + "confirm the result, then POST /api/strategies to create it")
    public com.tradeanalysis.strategy.describe.StrategyDescriptionParser.Result parse(@RequestBody DescribeRequest body) {
        String d = body == null ? null : body.description();
        if (d != null && d.length() > 4000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description is too long (max 4000 characters)");
        return parser.parse(d);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new strategy")
    public Strategy create(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, String> params = (Map<String, String>) body.getOrDefault("parameters", Map.of());
        return svc.create(
                required(body, "name"),
                String.valueOf(body.getOrDefault("description", "")),
                required(body, "type"),
                params);
    }

    public record CloneRequest(Integer lots, String name) {}

    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Clone a strategy you can see (e.g. a shared master strategy) as your own, with your own lots")
    public Strategy cloneWithLots(@PathVariable Long id, @RequestBody CloneRequest body) {
        if (body == null || body.lots() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: lots");
        return svc.cloneWithLots(id, body.lots(), body.name());
    }

    private static String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: " + key);
        }
        return v.toString();
    }

    public record UpdateRequest(String name, String description, Map<String, String> parameters) {}

    @PutMapping("/{id}")
    @Operation(summary = "Edit your strategy: name, description and/or parameters (omitted = unchanged; the type can't change). "
            + "Parameters are locked while it trades PAPER/LIVE or has an open position. Shared strategies: admins only")
    public Strategy update(@PathVariable Long id, @RequestBody UpdateRequest body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to change");
        return svc.update(id, body.name(), body.description(), body.parameters());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete your strategy with its backtests and reports. Refused while it is in a portfolio, being "
            + "traded, or has paper/live trades on record. Shared strategies: admins only")
    public void delete(@PathVariable Long id) { svc.delete(id); }

    @GetMapping
    @Operation(summary = "List all strategies")
    public List<Strategy> list() { return svc.list(); }

    @GetMapping("/performance")
    @Operation(summary = "Latest 30/90/180/360-day backtest result per strategy (yours; admins: anyone's), for comparing profitability")
    public List<StrategyService.Performance> performance() { return svc.performance(); }

    @GetMapping("/shared")
    @Operation(summary = "Ids of the master strategies every user may see (read-only); all others are private to their creator")
    public java.util.Set<Long> shared() { return svc.sharedIds(); }

    @GetMapping("/{id}")
    @Operation(summary = "Get strategy by id")
    public Strategy get(@PathVariable Long id) { return svc.get(id); }
}
