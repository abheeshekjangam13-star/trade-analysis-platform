package com.tradeanalysis.strategy.compliance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;

/** The compliance switches and grievance contact (table compliance_control, one row). */
@Service
public class ComplianceSettings {

    public record Settings(boolean algoIdRequired, boolean liveNeedsTwoFactor, boolean liveNeedsPan,
                           String grievanceName, String grievanceEmail, String grievancePhone) {}

    /** The grievance contact users see (public). */
    public record Grievance(String name, String email, String phone) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ComplianceSettings(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public Settings get() {
        return jdbc.query("SELECT algo_id_required, live_needs_two_factor, live_needs_pan, grievance_name, grievance_email, grievance_phone "
                        + "FROM compliance_control WHERE id = 1",
                rs -> rs.next() ? new Settings(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getString(4), rs.getString(5), rs.getString(6))
                        : new Settings(false, false, false, null, null, null));
    }

    public Grievance grievance() {
        Settings s = get();
        return new Grievance(s.grievanceName(), s.grievanceEmail(), s.grievancePhone());
    }

    /** Changes the fields that are not null; blank grievance fields clear them. */
    public Settings update(Boolean algoIdRequired, Boolean liveNeedsTwoFactor, Boolean liveNeedsPan,
                           String grievanceName, String grievanceEmail, String grievancePhone) {
        Settings s = get();
        if (grievanceEmail != null && !grievanceEmail.isBlank() && !grievanceEmail.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
            throw new IllegalArgumentException("Enter a valid grievance email");
        jdbc.update("UPDATE compliance_control SET algo_id_required = ?, live_needs_two_factor = ?, live_needs_pan = ?, "
                        + "grievance_name = ?, grievance_email = ?, grievance_phone = ?, updated_at = ? WHERE id = 1",
                algoIdRequired == null ? s.algoIdRequired() : algoIdRequired,
                liveNeedsTwoFactor == null ? s.liveNeedsTwoFactor() : liveNeedsTwoFactor,
                liveNeedsPan == null ? s.liveNeedsPan() : liveNeedsPan,
                text(grievanceName, s.grievanceName(), 120), text(grievanceEmail, s.grievanceEmail(), 200),
                text(grievancePhone, s.grievancePhone(), 40), Timestamp.from(clock.instant()));
        return get();
    }

    private static String text(String in, String old, int max) {
        if (in == null) return old;
        String t = in.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
