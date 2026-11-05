package com.tradeanalysis.strategy.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;

/**
 * The HS256 signing key for login tokens: AUTH_JWT_SECRET if set (at least 32 characters), otherwise a random key
 * created once in table auth_key. api-gateway reads the same key to verify tokens.
 */
@Component
public class AuthKeys {

    private final byte[] key;

    public AuthKeys(JdbcTemplate jdbc, @Value("${auth.jwt-secret:}") String configured) {
        if (configured != null && !configured.isBlank()) {
            if (configured.length() < 32) throw new IllegalStateException("auth.jwt-secret (AUTH_JWT_SECRET) must be at least 32 characters");
            key = configured.getBytes(StandardCharsets.UTF_8);
            return;
        }
        String stored = jdbc.query("SELECT secret FROM auth_key WHERE id = 1", rs -> rs.next() ? rs.getString(1) : null);
        if (stored == null) {
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            stored = Base64.getEncoder().encodeToString(random);
            jdbc.update("INSERT INTO auth_key (id, secret, created_at) VALUES (1, ?, ?)", stored, Timestamp.from(Instant.now()));
        }
        key = stored.getBytes(StandardCharsets.UTF_8);
    }

    byte[] key() { return key.clone(); }
}
