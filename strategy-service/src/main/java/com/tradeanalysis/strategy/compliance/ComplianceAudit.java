package com.tradeanalysis.strategy.compliance;

import com.tradeanalysis.strategy.user.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The compliance audit trail (table compliance_audit): who changed a control, an Algo ID, live access, a LIVE switch,
 * two-factor or a PAN, and when. Rows are never updated or deleted. {@link #record} never fails the action it records;
 * {@link #recordOrFail} does, for changes that must not happen unrecorded (strategy modes: written in the same
 * transaction as the change, so neither is kept without the other).
 */
@Service
public class ComplianceAudit {

    private static final Logger log = LoggerFactory.getLogger(ComplianceAudit.class);

    public record Entry(long id, Instant at, Long userId, String username, String action, String details) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ComplianceAudit(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    /** Records an action by the current caller. */
    public void record(String action, String details) {
        Long userId = CurrentUser.id().orElse(null);
        record(userId, action, details);
    }

    public void record(Long userId, String action, String details) {
        try {
            recordOrFail(userId, action, details);
        } catch (RuntimeException e) {
            log.error("COMPLIANCE_AUDIT could not record {} {}: {}", action, details, e.getMessage());
        }
    }

    /** Records an action, throwing when it can't: call it inside the change's own transaction. */
    public void recordOrFail(Long userId, String action, String details) {
        String username = userId == null ? null
                : jdbc.query("SELECT username FROM app_user WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
        jdbc.update("INSERT INTO compliance_audit (created_at, user_id, username, action, details) VALUES (?, ?, ?, ?, ?)",
                Timestamp.from(clock.instant()), userId, username, cut(action, 64), cut(details, 2000));
        log.info("COMPLIANCE_AUDIT user={} action={} {}", username, action, details);
    }

    /** Entries from {@code from} (inclusive) to {@code to} (exclusive), newest first, at most 1000. */
    public List<Entry> between(Instant from, Instant to) {
        return jdbc.query("SELECT id, created_at, user_id, username, action, details FROM compliance_audit "
                        + "WHERE created_at >= ? AND created_at < ? ORDER BY created_at DESC, id DESC LIMIT 1000",
                (rs, i) -> new Entry(rs.getLong(1), rs.getTimestamp(2).toInstant(), (Long) rs.getObject(3, Long.class),
                        rs.getString(4), rs.getString(5), rs.getString(6)),
                Timestamp.from(from), Timestamp.from(to));
    }

    private static String cut(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max); }
}
