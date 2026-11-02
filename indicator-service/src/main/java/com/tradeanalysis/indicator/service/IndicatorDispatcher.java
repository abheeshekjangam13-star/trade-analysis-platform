package com.tradeanalysis.indicator.service;

import com.tradeanalysis.common.dto.IndicatorRequest;
import com.tradeanalysis.common.dto.IndicatorResult;
import com.tradeanalysis.common.exception.NotFoundException;
import com.tradeanalysis.common.indicator.Params;
import com.tradeanalysis.indicator.engine.IndicatorEngine;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the right engine by name and delegates computation.
 * New engines are auto-discovered via Spring DI — just implement IndicatorEngine.
 */
@Service
public class IndicatorDispatcher {

    private final Map<String, IndicatorEngine> engines;

    public IndicatorDispatcher(List<IndicatorEngine> engineList) {
        this.engines = engineList.stream()
                .collect(Collectors.toMap(e -> e.name().toUpperCase(), Function.identity()));
    }

    public IndicatorResult compute(IndicatorRequest req) {
        String key = req.indicator().toUpperCase();
        IndicatorEngine engine = engines.get(key);
        if (engine == null) {
            throw new NotFoundException("Unknown indicator: " + req.indicator()
                    + ". Available: " + engines.keySet());
        }
        return engine.compute(req.series(), new Params(req.parameters()));
    }

    public List<String> listIndicators() {
        return engines.keySet().stream().sorted().toList();
    }
}
