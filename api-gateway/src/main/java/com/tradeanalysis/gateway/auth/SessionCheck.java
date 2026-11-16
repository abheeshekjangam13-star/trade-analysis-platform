package com.tradeanalysis.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * The session id of a user's latest login (app_user.session_id, set by strategy-service on every login). A token
 * carrying another session id comes from an older login, which a newer one has replaced.
 */
@Component
public class SessionCheck {

    private static final Logger log = LoggerFactory.getLogger(SessionCheck.class);

    private final DatabaseClient db;

    public SessionCheck(DatabaseClient db) {
        this.db = db;
    }

    /**
     * The latest session id, or empty when the user has none yet (no login since sessions were added) or isn't
     * found. On a database error this is empty too (the token's signature is still checked): strategy-service makes
     * the same check on every request it serves, so a database hiccup doesn't lock everyone out at the gateway.
     */
    public Mono<Optional<String>> latest(String username) {
        return db.sql("SELECT session_id FROM app_user WHERE LOWER(username) = LOWER(:u)")
                .bind("u", username)
                .map(row -> Optional.ofNullable(row.get(0, String.class)))
                .first()
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    log.warn("SESSION_CHECK_UNAVAILABLE user={} {}", username, e.toString());
                    return Mono.just(Optional.empty());
                });
    }
}
