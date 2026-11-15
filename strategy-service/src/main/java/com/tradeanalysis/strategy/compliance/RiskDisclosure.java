package com.tradeanalysis.strategy.compliance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The live trading risk disclosure. A user (not an admin) accepts the current version before they can switch a
 * strategy to LIVE or place a live order; a new version (change {@link #VERSION}) asks everyone again.
 */
@Service
public class RiskDisclosure {

    public static final String VERSION = "2026-11";
    public static final List<String> POINTS = List.of(
            "You build your strategies yourself. SysGrow provides the form and runs the rules you set; it gives no tips, "
                    + "recommendations or advice, and does not choose strategies for you.",
            "Every rule of a strategy is visible to you (white-box). Read your settings before you switch a strategy to LIVE.",
            "LIVE strategies place real orders with real money on your own broker account, automatically, whenever their rules are met.",
            "Options trading carries a high risk of loss. You can lose more than you expect in a short time; past or backtested "
                    + "results do not guarantee future results.",
            "Orders can fail, fill late or fill at a different price because of the broker, the exchange, the network or market "
                    + "conditions. Stop losses are not guaranteed.",
            "You are responsible for your positions. Check your broker account, keep enough margin, and use your max day loss "
                    + "and the stop controls.",
            "Your orders carry the exchange Algo ID the platform is registered with, and are recorded for audit.");

    public record Status(String version, List<String> points, boolean accepted, Instant acceptedAt) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RiskDisclosure(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public Status status(Long userId) {
        Instant at = acceptedAt(userId);
        return new Status(VERSION, POINTS, at != null, at);
    }

    public boolean accepted(Long userId) { return acceptedAt(userId) != null; }

    /** Records acceptance of the current version (the version shown must be the current one). */
    public Status accept(Long userId, String version) {
        if (!VERSION.equals(version)) throw new IllegalArgumentException("The disclosure changed: read the current version and accept again");
        if (!accepted(userId))
            jdbc.update("INSERT INTO risk_acceptance (user_id, version, accepted_at) VALUES (?, ?, ?)", userId, VERSION, Timestamp.from(clock.instant()));
        return status(userId);
    }

    private Instant acceptedAt(Long userId) {
        if (userId == null) return null;
        return jdbc.query("SELECT accepted_at FROM risk_acceptance WHERE user_id = ? AND version = ?",
                rs -> rs.next() ? rs.getTimestamp(1).toInstant() : null, userId, VERSION);
    }
}
