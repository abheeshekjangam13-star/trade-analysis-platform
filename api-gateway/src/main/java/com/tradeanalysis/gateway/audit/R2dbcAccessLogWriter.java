package com.tradeanalysis.gateway.audit;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class R2dbcAccessLogWriter implements AccessLogWriter {

    private static final String INSERT = """
            INSERT INTO access_log (username, method, path, query, status_code, duration_ms, client_ip, user_agent, created_at)
            VALUES (:username, :method, :path, :query, :status, :duration, :ip, :agent, :created)""";

    private final DatabaseClient db;

    public R2dbcAccessLogWriter(DatabaseClient db) { this.db = db; }

    @Override
    public Mono<Void> write(AccessLogEntry e) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(INSERT);
        spec = bind(spec, "username", e.username(), String.class);
        spec = spec.bind("method", e.method()).bind("path", e.path());
        spec = bind(spec, "query", e.query(), String.class);
        spec = bind(spec, "status", e.statusCode(), Integer.class);
        spec = spec.bind("duration", e.durationMs());
        spec = bind(spec, "ip", e.clientIp(), String.class);
        spec = bind(spec, "agent", e.userAgent(), String.class);
        spec = spec.bind("created", e.createdAt().atOffset(java.time.ZoneOffset.UTC));
        return spec.then();
    }

    private static <T> DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, String name,
                                                              T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
}
