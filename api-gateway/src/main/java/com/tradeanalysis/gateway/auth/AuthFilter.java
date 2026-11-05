package com.tradeanalysis.gateway.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Login check for every /api request: requires "Authorization: Bearer <token>" signed by strategy-service, then
 * passes the verified username on as X-User (any X-User a client sends is removed, so it cannot be faked).
 * Open without a token: /api/auth/status, /plans, /signup, /setup, /login, and the Razorpay webhook. /api/market-data/admin/** needs ADMIN.
 */
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    public static final String USER_ATTR = "auth.user";
    static final String USER_HEADER = "X-User";
    static final String ROLES_HEADER = "X-User-Roles";
    static final String ISSUER = "trade-analysis";
    // The Razorpay webhook has no token; strategy-service verifies its HMAC signature.
    static final Set<String> PUBLIC = Set.of("/api/auth/status", "/api/auth/plans", "/api/auth/signup", "/api/auth/setup", "/api/auth/login",
            "/api/payments/webhook");
    /** Kite session and instrument admin in market-data-service, which has no user checks of its own. */
    static final String ADMIN_PREFIX = "/api/market-data/admin/";
    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final GatewayAuthKey key;
    private final boolean enabled;

    public AuthFilter(GatewayAuthKey key, @Value("${auth.enabled:true}") boolean enabled) {
        this.key = key;
        this.enabled = enabled;
    }

    record Verified(String username, List<String> roles) {}

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/")) return chain.filter(exchange);
        if (!enabled) {   // development only: trust the client's X-User
            String u = exchange.getRequest().getHeaders().getFirst(USER_HEADER);
            if (u != null) exchange.getAttributes().put(USER_ATTR, u);
            return chain.filter(exchange);
        }
        if (PUBLIC.contains(path)) return chain.filter(withUser(exchange, null));
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7) || auth.length() < 8)
            return reject(exchange, "Log in first");
        String token = auth.substring(7).trim();
        return key.get()
                .switchIfEmpty(Mono.error(new IllegalStateException("No signing key yet: start strategy-service once")))
                .flatMap(k -> {
                    Verified v = verify(token, k);
                    if (v == null) return reject(exchange, "Session expired or invalid: log in again");
                    exchange.getAttributes().put(USER_ATTR, v.username());
                    if (path.startsWith(ADMIN_PREFIX) && !v.roles().contains("ADMIN"))
                        return respond(exchange, HttpStatus.FORBIDDEN, "Only an ADMIN can do this");
                    return chain.filter(withUser(exchange, v));
                })
                .onErrorResume(IllegalStateException.class, e -> {
                    log.warn("AUTH_UNAVAILABLE {}", e.getMessage());
                    return respond(exchange, HttpStatus.SERVICE_UNAVAILABLE, "Login is not available yet: " + e.getMessage());
                });
    }

    /** A valid, unexpired HS256 token from our issuer, or null. */
    static Verified verify(String token, byte[] key) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(key))) return null;
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            Date exp = c.getExpirationTime();
            if (!ISSUER.equals(c.getIssuer()) || exp == null || exp.toInstant().isBefore(Instant.now()) || c.getSubject() == null) return null;
            List<String> roles = c.getStringListClaim("roles");
            return new Verified(c.getSubject(), roles == null ? List.of() : roles);
        } catch (Exception e) {
            return null;
        }
    }

    private static ServerWebExchange withUser(ServerWebExchange exchange, Verified v) {
        ServerHttpRequest req = exchange.getRequest().mutate().headers(h -> {
            h.remove(USER_HEADER);
            h.remove(ROLES_HEADER);
            if (v != null) {
                h.set(USER_HEADER, v.username());
                h.set(ROLES_HEADER, String.join(",", v.roles()));
            }
        }).build();
        return exchange.mutate().request(req).build();
    }

    private static Mono<Void> reject(ServerWebExchange exchange, String message) {
        return respond(exchange, HttpStatus.UNAUTHORIZED, message);
    }

    private static Mono<Void> respond(ServerWebExchange exchange, HttpStatus status, String message) {
        var res = exchange.getResponse();
        res.setStatusCode(status);
        res.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":" + status.value() + ",\"error\":\"" + status.getReasonPhrase() + "\",\"message\":\""
                + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"path\":\""
                + exchange.getRequest().getPath().value().replace("\"", "") + "\"}";
        DataBuffer buf = res.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return res.writeWith(Mono.just(buf));
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }   // after the access log, so 401s are logged too
}
