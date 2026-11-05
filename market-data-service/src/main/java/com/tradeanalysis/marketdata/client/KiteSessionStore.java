package com.tradeanalysis.marketdata.client;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Persists the single current Kite session (table kite_session). */
@Component
public class KiteSessionStore {

    public record Session(String apiKey, String accessToken, String userId, String userName, Instant updatedAt) {}

    private final JdbcTemplate jdbc;

    public KiteSessionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Session> load() {
        return jdbc.query("SELECT api_key, access_token, user_id, user_name, updated_at FROM kite_session WHERE id = 1",
                rs -> rs.next() ? Optional.of(new Session(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant())) : Optional.empty());
    }

    public void save(String apiKey, String accessToken, String userId, String userName) {
        Timestamp now = Timestamp.from(Instant.now());
        int n = jdbc.update("UPDATE kite_session SET api_key = ?, access_token = ?, user_id = ?, user_name = ?, updated_at = ? WHERE id = 1",
                apiKey, accessToken, userId, userName, now);
        if (n == 0)
            jdbc.update("INSERT INTO kite_session (id, api_key, access_token, user_id, user_name, updated_at) VALUES (1, ?, ?, ?, ?, ?)",
                    apiKey, accessToken, userId, userName, now);
    }
}
