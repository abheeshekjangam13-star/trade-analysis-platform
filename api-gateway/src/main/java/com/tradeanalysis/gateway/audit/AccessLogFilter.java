package com.tradeanalysis.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;

/**
 * Records every /api/** request (who, what, result, duration) in the access log. The write happens after the
 * response and in the background, so a slow or unavailable database never delays or fails a request.
 */
@Component
public class AccessLogFilter implements GlobalFilter, Ordered {

    public static final String USER_HEADER = "X-User";
    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    private final AccessLogWriter writer;
    private final Clock clock;

    @Autowired
    public AccessLogFilter(AccessLogWriter writer) { this(writer, Clock.systemUTC()); }

    AccessLogFilter(AccessLogWriter writer, Clock clock) { this.writer = writer; this.clock = clock; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        if (!req.getPath().value().startsWith("/api/")) return chain.filter(exchange);
        Instant start = clock.instant();
        return chain.filter(exchange).doFinally(signal -> {
            AccessLogEntry entry = entry(exchange, start, clock.instant());
            writer.write(entry).subscribe(null, e -> log.warn("Could not write access log for {} {}: {}",
                    entry.method(), entry.path(), e.getMessage()));
        });
    }

    static AccessLogEntry entry(ServerWebExchange exchange, Instant start, Instant end) {
        ServerHttpRequest req = exchange.getRequest();
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        return new AccessLogEntry(
                cut((String) exchange.getAttributes().get(com.tradeanalysis.gateway.auth.AuthFilter.USER_ATTR), 64),   // verified user only
                req.getMethod().name(),
                cut(req.getPath().value(), 1024),
                cut(redact(req.getURI().getRawQuery()), 2048),
                status == null ? null : status.value(),
                Math.max(0, end.toEpochMilli() - start.toEpochMilli()),
                cut(clientIp(req), 64),
                cut(req.getHeaders().getFirst("User-Agent"), 512),
                end);
    }

    private static final java.util.regex.Pattern SECRET_PARAM = java.util.regex.Pattern.compile(
            "(?i)((?:^|&)[^=&]*(?:token|secret|password|checksum)[^=&]*=)[^&]*");

    /** Masks values of token/secret/password-like query parameters (e.g. Kite access or request tokens). */
    static String redact(String query) {
        return query == null ? null : SECRET_PARAM.matcher(query).replaceAll("$1***");
    }

    private static String clientIp(ServerHttpRequest req) {
        String forwarded = req.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        InetSocketAddress remote = req.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress();
    }

    private static String cut(String value, int max) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
