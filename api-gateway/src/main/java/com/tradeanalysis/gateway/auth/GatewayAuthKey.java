package com.tradeanalysis.gateway.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** The token signing key: AUTH_JWT_SECRET if set, otherwise the key strategy-service stored in auth_key. */
@Component
public class GatewayAuthKey {

    private final DatabaseClient db;
    private final AtomicReference<byte[]> key = new AtomicReference<>();

    public GatewayAuthKey(DatabaseClient db, @Value("${auth.jwt-secret:}") String configured) {
        this.db = db;
        if (configured != null && !configured.isBlank()) key.set(configured.getBytes(StandardCharsets.UTF_8));
    }

    public Mono<byte[]> get() {
        byte[] k = key.get();
        if (k != null) return Mono.just(k);
        return db.sql("SELECT secret FROM auth_key WHERE id = 1").map(row -> row.get(0, String.class)).one()
                .map(s -> s.getBytes(StandardCharsets.UTF_8))
                .doOnNext(key::set);
    }
}
