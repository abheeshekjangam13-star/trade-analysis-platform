package com.tradeanalysis.strategy.compliance;

import com.tradeanalysis.strategy.trading.KiteSecrets;
import com.tradeanalysis.strategy.user.ClientIds;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Each user's PAN, linked to their client ID (table user_pan). The PAN is stored encrypted and shown masked
 * (ABCDE1234F -> XXXXX1234X); a keyed fingerprint keeps one PAN to one account. The name is the name as on the PAN
 * card (the sign-up name). An admin marks a PAN verified after checking it; changing it afterwards needs an admin.
 */
@Service
public class PanRegistry {

    /** PAN format: 5 letters, 4 digits, 1 letter; the 4th letter is the holder type (P = individual, F = firm, …). */
    static final Pattern PAN = Pattern.compile("[A-Z]{3}[ABCFGHLJPTK][A-Z]\\d{4}[A-Z]");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z .'-]{1,119}");

    public record Kyc(String clientCode, boolean panAdded, String panMasked, String nameOnPan, boolean verified,
                      Instant verifiedAt, Instant addedAt) {}
    public record Row(Long userId, String clientCode, String username, String panMasked, String nameOnPan, boolean verified, Instant addedAt) {}

    private final JdbcTemplate jdbc;
    private final KiteSecrets secrets;
    private final Clock clock;

    public PanRegistry(JdbcTemplate jdbc, KiteSecrets secrets, Clock clock) { this.jdbc = jdbc; this.secrets = secrets; this.clock = clock; }

    /** The PAN in capitals without spaces, after checking its format. */
    public static String normalize(String pan) {
        String p = pan == null ? "" : pan.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!PAN.matcher(p).matches()) throw new IllegalArgumentException("Enter a valid PAN (like ABCDE1234F)");
        return p;
    }

    /** The name as on the PAN card, after checking it. */
    public static String normalizeName(String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (!NAME.matcher(n).matches()) throw new IllegalArgumentException("Enter your full name exactly as on your PAN card (letters and spaces)");
        return n;
    }

    static String mask(String pan) { return "XXXXX" + pan.substring(5, 9) + "X"; }

    /** Whether another account already uses this PAN. */
    public boolean taken(String pan, Long exceptUserId) {
        Long owner = jdbc.query("SELECT user_id FROM user_pan WHERE pan_hash = ?", rs -> rs.next() ? rs.getLong(1) : null, secrets.fingerprint(normalize(pan)));
        return owner != null && !owner.equals(exceptUserId);
    }

    public Kyc kyc(Long userId) {
        String code = userId == null || userId <= 0 || userId >= 1_000_000 ? null : ClientIds.code(userId);
        return jdbc.query("SELECT pan_masked, name_on_pan, verified, verified_at, created_at FROM user_pan WHERE user_id = ?",
                rs -> rs.next() ? new Kyc(code, true, rs.getString(1), rs.getString(2), rs.getBoolean(3), ts(rs.getTimestamp(4)), ts(rs.getTimestamp(5)))
                        : new Kyc(code, false, null, null, false, null, null), userId);
    }

    public boolean verified(Long userId) { return userId != null && kyc(userId).verified(); }

    /**
     * Saves the user's PAN and name as on the PAN card. A user can add it once; after that only an admin
     * ({@code byAdmin}) can change it. A changed PAN or name needs verifying again.
     */
    public Kyc save(Long userId, String pan, String nameOnPan, boolean byAdmin) {
        String p = normalize(pan);
        String n = normalizeName(nameOnPan);
        Optional<String> existing = Optional.ofNullable(jdbc.query("SELECT pan_hash FROM user_pan WHERE user_id = ?", rs -> rs.next() ? rs.getString(1) : null, userId));
        if (existing.isPresent() && !byAdmin)
            throw new IllegalStateException("Your PAN is already saved. To change it, contact support");
        if (taken(p, userId)) throw new DuplicateKeyException("This PAN is already linked to another account");
        jdbc.update("DELETE FROM user_pan WHERE user_id = ?", userId);
        try {
            jdbc.update("INSERT INTO user_pan (user_id, pan_enc, pan_hash, pan_masked, name_on_pan, verified, created_at) VALUES (?, ?, ?, ?, ?, FALSE, ?)",
                    userId, secrets.encrypt(p), secrets.fingerprint(p), mask(p), n, Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            throw new DuplicateKeyException("This PAN is already linked to another account");
        }
        return kyc(userId);
    }

    /** An admin marks the PAN verified (or not). */
    public Kyc setVerified(Long userId, boolean verified, Long byUserId) {
        int n = jdbc.update("UPDATE user_pan SET verified = ?, verified_at = ?, verified_by = ? WHERE user_id = ?",
                verified, verified ? Timestamp.from(clock.instant()) : null, verified ? byUserId : null, userId);
        if (n == 0) throw new IllegalStateException("This user has not added a PAN yet");
        return kyc(userId);
    }

    /** The full PAN, for an admin checking it against the PAN card. */
    public String reveal(Long userId) {
        String enc = jdbc.query("SELECT pan_enc FROM user_pan WHERE user_id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
        if (enc == null) throw new IllegalStateException("This user has not added a PAN yet");
        return secrets.decrypt(enc);
    }

    public void remove(Long userId) { jdbc.update("DELETE FROM user_pan WHERE user_id = ?", userId); }

    /** Every user's KYC status, for the admin's Users page. */
    public List<Row> all() {
        return jdbc.query("SELECT u.id, u.username, p.pan_masked, p.name_on_pan, p.verified, p.created_at FROM app_user u "
                        + "LEFT JOIN user_pan p ON p.user_id = u.id ORDER BY u.id",
                (rs, i) -> new Row(rs.getLong(1), rs.getLong(1) < 1_000_000 ? ClientIds.code(rs.getLong(1)) : null, rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getBoolean(5), ts(rs.getTimestamp(6))));
    }

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }
}
