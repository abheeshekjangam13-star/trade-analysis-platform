package com.tradeanalysis.strategy.compliance;

import com.tradeanalysis.strategy.trading.KiteSecrets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/**
 * Two-factor sign-in with an authenticator app (TOTP, RFC 6238: SHA-1, 6 digits, 30-second steps, one step of clock
 * drift allowed). The secret is encrypted at rest; a code is accepted once (replays of the same step are refused).
 */
@Service
public class TwoFactor {

    public record Status(boolean enabled, Instant enabledAt) {}
    /** Shown once while setting up: the secret to type in, and the otpauth:// link a QR code is made from. */
    public record Setup(String secret, String otpauthUri) {}

    private static final String ISSUER = "SysGrow";
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private final JdbcTemplate jdbc;
    private final KiteSecrets secrets;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TwoFactor(JdbcTemplate jdbc, KiteSecrets secrets, Clock clock) { this.jdbc = jdbc; this.secrets = secrets; this.clock = clock; }

    public Status status(Long userId) {
        return jdbc.query("SELECT enabled, enabled_at FROM user_totp WHERE user_id = ?",
                rs -> rs.next() && rs.getBoolean(1) ? new Status(true, rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant())
                        : new Status(false, null), userId);
    }

    public boolean enabled(Long userId) { return userId != null && status(userId).enabled(); }

    /** A new secret (replacing an unfinished setup). Refused while two-factor is on: turn it off first. */
    public Setup setup(Long userId, String accountLabel) {
        if (enabled(userId)) throw new IllegalStateException("Two-factor sign-in is already on: turn it off first to set up a new device");
        byte[] raw = new byte[20];
        random.nextBytes(raw);
        String secret = base32(raw);
        jdbc.update("DELETE FROM user_totp WHERE user_id = ?", userId);
        jdbc.update("INSERT INTO user_totp (user_id, secret_enc, enabled, created_at) VALUES (?, ?, FALSE, ?)",
                userId, secrets.encrypt(secret), Timestamp.from(clock.instant()));
        String label = URLEncoder.encode(ISSUER + ":" + accountLabel, StandardCharsets.UTF_8).replace("+", "%20");
        return new Setup(secret, "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + ISSUER + "&algorithm=SHA1&digits=6&period=30");
    }

    /** Turns two-factor on after the first code from the app checks out. */
    public Status enable(Long userId, String code) {
        String secret = secret(userId);
        if (secret == null) throw new IllegalStateException("Set up two-factor sign-in first");
        if (!check(userId, secret, code)) throw new IllegalArgumentException("That code is not right: enter the current 6-digit code from your app");
        jdbc.update("UPDATE user_totp SET enabled = TRUE, enabled_at = ? WHERE user_id = ?", Timestamp.from(clock.instant()), userId);
        return status(userId);
    }

    /** Turns two-factor off (needs a current code). */
    public void disable(Long userId, String code) {
        if (!enabled(userId)) return;
        if (!verify(userId, code)) throw new IllegalArgumentException("That code is not right: enter the current 6-digit code from your app");
        jdbc.update("DELETE FROM user_totp WHERE user_id = ?", userId);
    }

    /** Removes a user's two-factor (an admin, when a user lost their phone). */
    public void reset(Long userId) { jdbc.update("DELETE FROM user_totp WHERE user_id = ?", userId); }

    /** Checks a sign-in code for a user who has two-factor on. */
    public boolean verify(Long userId, String code) {
        if (!enabled(userId)) return false;
        String secret = secret(userId);
        return secret != null && check(userId, secret, code);
    }

    private boolean check(Long userId, String secret, String code) {
        if (code == null) return false;
        String c = code.replaceAll("\\s", "");
        if (!c.matches("\\d{6}")) return false;
        long now = clock.instant().getEpochSecond() / 30;
        Long last = jdbc.query("SELECT last_step FROM user_totp WHERE user_id = ?", rs -> rs.next() ? (Long) rs.getObject(1, Long.class) : null, userId);
        byte[] key = base32Decode(secret);
        for (long step = now - 1; step <= now + 1; step++) {
            if (last != null && step <= last) continue;   // each code works once
            if (code(key, step).equals(c)) {
                jdbc.update("UPDATE user_totp SET last_step = ? WHERE user_id = ?", step, userId);
                return true;
            }
        }
        return false;
    }

    private String secret(Long userId) {
        String enc = jdbc.query("SELECT secret_enc FROM user_totp WHERE user_id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
        return enc == null ? null : secrets.decrypt(enc);
    }

    /** The 6-digit code of a time step (RFC 4226 HOTP with HMAC-SHA1). */
    static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) { sb.append(BASE32[(buffer >> (bits - 5)) & 31]); bits -= 5; }
        }
        if (bits > 0) sb.append(BASE32[(buffer << (5 - bits)) & 31]);
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String t = s.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(t.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char ch : t.toCharArray()) {
            int v = new String(BASE32).indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("Bad base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) { out.put((byte) ((buffer >> (bits - 8)) & 0xff)); bits -= 8; }
        }
        return out.array();
    }
}
