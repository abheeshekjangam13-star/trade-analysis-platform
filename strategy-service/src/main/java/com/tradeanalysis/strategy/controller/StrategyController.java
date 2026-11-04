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

    public StrategyController(StrategyService svc) { this.svc = svc; }

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

    private static String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: " + key);
        }
        return v.toString();
    }

    @GetMapping
    @Operation(summary = "List all strategies")
    public List<Strategy> list() { return svc.list(); }

    @GetMapping("/{id}")
    @Operation(summary = "Get strategy by id")
    public Strategy get(@PathVariable Long id) { return svc.get(id); }
}
