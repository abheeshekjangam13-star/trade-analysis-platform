package com.tradeanalysis.strategy.trading;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** The Kite session market-data-service stores after login (table kite_session). The token never leaves the server. */
@Component
public class KiteSession {

    public record Credentials(String apiKey, String accessToken, String userId, String userName) {
        public String authHeader() { return "token " + apiKey + ":" + accessToken; }
    }

    private final JdbcTemplate jdbc;

    public KiteSession(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Credentials> current() {
        try {
            return jdbc.query("SELECT api_key, access_token, user_id, user_name FROM kite_session WHERE id = 1",
                    rs -> rs.next() ? Optional.of(new Credentials(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                            : Optional.empty());
        } catch (org.springframework.dao.DataAccessException e) {
            return Optional.empty();   // table not created yet (market-data-service not migrated)
        }
    }

    public Credentials require() {
        return current().orElseThrow(() -> new TradingException("Kite is not connected: log in to Kite first"));
    }
}
