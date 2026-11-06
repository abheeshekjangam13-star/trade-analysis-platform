package com.tradeanalysis.strategy.trading;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;

/**
 * Encrypts users' Kite API secrets and access tokens (AES-256-GCM). The key is KITE_ENCRYPTION_KEY (base64 of 32
 * bytes, e.g. {@code openssl rand -base64 32}) if set, otherwise a random key created once in table kite_account_key.
 * Set the environment variable in production so a copy of the database alone does not reveal the secrets.
 */
@Component
public class KiteSecrets {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public KiteSecrets(JdbcTemplate jdbc, @Value("${kite.encryption-key:}") String configured) {
        byte[] raw;
        if (configured != null && !configured.isBlank()) {
            raw = Base64.getDecoder().decode(configured.trim());
            if (raw.length != 32) throw new IllegalStateException("kite.encryption-key (KITE_ENCRYPTION_KEY) must be base64 of 32 bytes");
        } else {
            String stored = jdbc.query("SELECT secret FROM kite_account_key WHERE id = 1", rs -> rs.next() ? rs.getString(1) : null);
            if (stored == null) {
                byte[] fresh = new byte[32];
                random.nextBytes(fresh);
                stored = Base64.getEncoder().encodeToString(fresh);
                jdbc.update("INSERT INTO kite_account_key (id, secret, created_at) VALUES (1, ?, ?)", stored, Timestamp.from(Instant.now()));
            }
            raw = Base64.getDecoder().decode(stored);
        }
        key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + enc.length).put(iv).put(enc).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new TradingException("Your saved Kite details cannot be read (the server's encryption key changed): save your API key and secret again");
        }
    }
}
