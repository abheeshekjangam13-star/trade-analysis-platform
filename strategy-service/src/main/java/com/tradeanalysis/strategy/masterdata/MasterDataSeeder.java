package com.tradeanalysis.strategy.masterdata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the master strategies (src/main/resources/master-data/strategies.json) in the database: on every start any
 * that are missing are recreated with their original id, name, type, description and parameters. Existing rows are
 * never changed. Disable with {@code master-data.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "master-data.enabled", havingValue = "true", matchIfMissing = true)
public class MasterDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MasterDataSeeder.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MasterStrategy(long id, String name, String type, String description, Map<String, String> parameters) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MasterFile(List<MasterStrategy> strategies) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Resource file;

    public MasterDataSeeder(JdbcTemplate jdbc, ObjectMapper json,
                            @Value("classpath:master-data/strategies.json") Resource file) {
        this.jdbc = jdbc; this.json = json; this.file = file;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException { seed(); }

    /** Restores missing master strategies; returns how many were inserted. */
    @Transactional
    public int seed() throws IOException {
        List<MasterStrategy> strategies;
        try (InputStream in = file.getInputStream()) {
            strategies = json.readValue(in, MasterFile.class).strategies();
        }
        int restored = 0;
        for (MasterStrategy s : strategies) {
            String existingName = jdbc.query("SELECT name FROM strategy WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, s.id());
            if (existingName != null) {
                if (!existingName.equals(s.name()))
                    log.warn("Master strategy #{} is '{}' in the database but '{}' in master data; left as is",
                            s.id(), existingName, s.name());
                continue;
            }
            Long otherId = jdbc.query("SELECT id FROM strategy WHERE name = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, s.name());
            if (otherId != null) {
                log.warn("Master strategy '{}' exists with id #{} instead of #{}; not restored", s.name(), otherId, s.id());
                continue;
            }
            jdbc.update("INSERT INTO strategy (id, name, description, type, created_at) VALUES (?, ?, ?, ?, ?)",
                    s.id(), s.name(), s.description(), s.type(), Timestamp.from(Instant.now()));
            s.parameters().forEach((k, v) ->
                    jdbc.update("INSERT INTO strategy_param (strategy_id, param_key, param_value) VALUES (?, ?, ?)", s.id(), k, v));
            log.info("Restored master strategy #{} '{}' ({} parameters)", s.id(), s.name(), s.parameters().size());
            restored++;
        }
        if (restored > 0) realignStrategyIds();
        return restored;
    }

    /** New strategies must get ids after the restored ones. */
    private void realignStrategyIds() {
        long next = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM strategy", Long.class);
        String db = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName()).toLowerCase(Locale.ROOT);
        if (db.contains("postgres"))
            jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('strategy', 'id'), ?, false)", Long.class, next);
        else
            jdbc.execute("ALTER TABLE strategy ALTER COLUMN id RESTART WITH " + next);
    }
}
