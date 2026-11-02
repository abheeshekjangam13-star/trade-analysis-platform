package com.tradeanalysis.indicator.controller;

import com.tradeanalysis.common.dto.IndicatorRequest;
import com.tradeanalysis.common.dto.IndicatorResult;
import com.tradeanalysis.indicator.service.IndicatorDispatcher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/indicators")
@Tag(name = "Indicators", description = "Compute technical indicators over a PriceSeries")
public class IndicatorController {

    private final IndicatorDispatcher dispatcher;

    public IndicatorController(IndicatorDispatcher dispatcher) { this.dispatcher = dispatcher; }

    @PostMapping("/compute")
    @Operation(summary = "Run a single indicator. Body: {indicator, series, parameters}")
    public IndicatorResult compute(@Valid @RequestBody IndicatorRequest req) {
        return dispatcher.compute(req);
    }

    @GetMapping
    @Operation(summary = "List all supported indicator names")
    public List<String> list() {
        return dispatcher.listIndicators();
    }
}
