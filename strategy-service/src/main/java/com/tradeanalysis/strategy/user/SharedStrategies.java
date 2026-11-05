package com.tradeanalysis.strategy.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Strategies every user may see (read-only): the master strategies in master-data/strategies.json (#55, #56).
 * Everything else is private to the user who created it. Backtests, reports and trades on shared strategies still
 * belong to whoever ran them.
 */
@Component
public class SharedStrategies {

    private final Set<Long> ids;

    public SharedStrategies(ObjectMapper json, @Value("classpath:master-data/strategies.json") Resource file) throws IOException {
        Set<Long> found = new LinkedHashSet<>();
        try (InputStream in = file.getInputStream()) {
            for (JsonNode s : json.readTree(in).path("strategies")) found.add(s.path("id").asLong());
        }
        this.ids = Set.copyOf(found);
    }

    public boolean contains(Long strategyId) { return strategyId != null && ids.contains(strategyId); }

    public Set<Long> ids() { return ids; }
}
