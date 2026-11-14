package com.tradeanalysis.strategy.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.trading.KiteSecrets;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone alerts (Web Push): keeps each user's subscribed browsers and sends them alerts in the background, so a slow
 * push service never holds up trading. Dead subscriptions (404/410, or 10 failures in a row) are removed.
 *
 * VAPID keys come from PUSH_VAPID_PUBLIC_KEY / PUSH_VAPID_PRIVATE_KEY (base64url: 65-byte point, 32-byte scalar) if
 * set, otherwise a pair is created once and kept in push_vapid_key. Changing keys makes browsers subscribe again.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final int MAX_DEVICES = 10;
    private static final int MAX_FAILURES = 10;

    /** One alert: shown as a notification; tapping it opens url. Same tag replaces an older alert on the phone. */
    public record Alert(String title, String body, String url, String tag, int ttlSeconds) {
        public Alert {
            if (body != null && body.length() > 600) body = body.substring(0, 597) + "…";
            if (ttlSeconds <= 0) ttlSeconds = 3600;
        }
    }

    private record Sub(long id, String endpoint, String p256dh, String auth) {}
    private record Keys(String publicKey, ECPrivateKey privateKey) {}

    private final JdbcTemplate jdbc;
    private final KiteSecrets secrets;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String configuredPublic, configuredPrivate, subject;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "push-sender");
        t.setDaemon(true);
        return t;
    });
    private volatile Keys keys;

    public PushService(JdbcTemplate jdbc, KiteSecrets secrets, ObjectMapper json,
                       @Value("${push.enabled:true}") boolean enabled,
                       @Value("${push.vapid.public-key:}") String configuredPublic,
                       @Value("${push.vapid.private-key:}") String configuredPrivate,
                       @Value("${push.vapid.subject:${notifications.app-url:http://localhost:5173}}") String subject) {
        this.jdbc = jdbc; this.secrets = secrets; this.json = json; this.enabled = enabled;
        this.configuredPublic = configuredPublic; this.configuredPrivate = configuredPrivate; this.subject = subject;
    }

    @PreDestroy
    void stop() { pool.shutdownNow(); }

    public boolean enabled() { return enabled; }

    /** The VAPID public key (base64url) browsers subscribe with, or null when push is off. */
    public String publicKey() { return enabled ? keys().publicKey() : null; }

    // ---- subscriptions --------------------------------------------------------------------------------------------

    /** Saves (or moves to this user) a browser's subscription. Keeps the newest {@value #MAX_DEVICES} per user. */
    public void subscribe(Long userId, String endpoint, String p256dh, String auth, String userAgent) {
        if (!enabled) throw new IllegalArgumentException("Phone alerts are turned off on this server");
        if (endpoint == null || endpoint.length() > 1000 || !WebPush.allowedEndpoint(endpoint))
            throw new IllegalArgumentException("Not a push service address this server sends to");
        try {
            WebPush.publicKey(WebPush.B64D.decode(p256dh));
            if (WebPush.B64D.decode(auth).length != 16) throw new IllegalArgumentException("auth");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The browser's push keys are not valid");
        }
        String ua = userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length()));
        jdbc.update("DELETE FROM push_subscription WHERE endpoint = ?", endpoint);
        jdbc.update("INSERT INTO push_subscription (user_id, endpoint, p256dh, auth, user_agent, created_at, failures) VALUES (?, ?, ?, ?, ?, ?, 0)",
                userId, endpoint, p256dh, auth, ua, Timestamp.from(Instant.now()));
        List<Long> extra = jdbc.queryForList(
                "SELECT id FROM push_subscription WHERE user_id = ? ORDER BY created_at DESC, id DESC OFFSET " + MAX_DEVICES + " ROWS",
                Long.class, userId);
        extra.forEach(id -> jdbc.update("DELETE FROM push_subscription WHERE id = ?", id));
    }

    public void unsubscribe(Long userId, String endpoint) {
        jdbc.update("DELETE FROM push_subscription WHERE user_id = ? AND endpoint = ?", userId, endpoint);
    }

    public int devices(Long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM push_subscription WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    /** Users with at least one subscribed device. */
    public List<Long> subscribedUsers() {
        return jdbc.queryForList("SELECT DISTINCT user_id FROM push_subscription", Long.class);
    }

    // ---- sending --------------------------------------------------------------------------------------------------

    /** Sends the alert to every device of the user, in the background. Never throws. */
    public void notify(Long userId, Alert alert) {
        if (!enabled || userId == null || alert == null) return;
        try {
            pool.submit(() -> send(userId, alert));
        } catch (RuntimeException e) {
            log.warn("PUSH not queued for user {}: {}", userId, e.getMessage());
        }
    }

    /** Sends now on this thread; returns how many devices accepted it. */
    int send(Long userId, Alert alert) {
        List<Sub> subs = jdbc.query("SELECT id, endpoint, p256dh, auth FROM push_subscription WHERE user_id = ?",
                (rs, i) -> new Sub(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), userId);
        if (subs.isEmpty()) return 0;
        byte[] payload;
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", alert.title());
            m.put("body", alert.body());
            m.put("url", alert.url() == null ? "/" : alert.url());
            if (alert.tag() != null) m.put("tag", alert.tag());
            payload = json.writeValueAsString(m).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("PUSH payload failed: {}", e.getMessage());
            return 0;
        }
        int ok = 0;
        for (Sub s : subs) {
            try {
                if (!WebPush.allowedEndpoint(s.endpoint())) { delete(s.id()); continue; }
                byte[] body = WebPush.encrypt(payload, WebPush.B64D.decode(s.p256dh()), WebPush.B64D.decode(s.auth()));
                Keys k = keys();
                HttpRequest req = HttpRequest.newBuilder(URI.create(s.endpoint()))
                        .timeout(Duration.ofSeconds(10))
                        .header("TTL", String.valueOf(alert.ttlSeconds()))
                        .header("Urgency", "high")
                        .header("Content-Encoding", "aes128gcm")
                        .header("Content-Type", "application/octet-stream")
                        .header("Authorization", WebPush.vapidHeader(s.endpoint(), k.privateKey(), k.publicKey(), subject, Instant.now()))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status >= 200 && status < 300) {
                    jdbc.update("UPDATE push_subscription SET last_sent_at = ?, failures = 0 WHERE id = ?", Timestamp.from(Instant.now()), s.id());
                    ok++;
                } else if (status == 404 || status == 410) {
                    delete(s.id());   // the browser unsubscribed or the app was uninstalled
                } else {
                    failed(s.id(), "HTTP " + status);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ok;
            } catch (Exception e) {
                failed(s.id(), e.getMessage());
            }
        }
        return ok;
    }

    private void delete(long id) { jdbc.update("DELETE FROM push_subscription WHERE id = ?", id); }

    private void failed(long id, String why) {
        log.warn("PUSH failed subscription={}: {}", id, why);
        jdbc.update("UPDATE push_subscription SET failures = failures + 1 WHERE id = ?", id);
        jdbc.update("DELETE FROM push_subscription WHERE id = ? AND failures >= ?", id, MAX_FAILURES);
    }

    // ---- VAPID keys -----------------------------------------------------------------------------------------------

    private Keys keys() {
        Keys k = keys;
        if (k == null) {
            synchronized (this) {
                if (keys == null) keys = loadKeys();
                k = keys;
            }
        }
        return k;
    }

    private Keys loadKeys() {
        if (!configuredPublic.isBlank() && !configuredPrivate.isBlank()) {
            WebPush.publicKey(WebPush.B64D.decode(configuredPublic.trim()));   // fail fast on a bad value
            return new Keys(configuredPublic.trim(), WebPush.privateKey(WebPush.B64D.decode(configuredPrivate.trim())));
        }
        List<String[]> rows = jdbc.query("SELECT public_key, private_key_enc FROM push_vapid_key WHERE id = 1",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)});
        if (!rows.isEmpty()) {
            return new Keys(rows.get(0)[0], WebPush.privateKey(WebPush.B64D.decode(secrets.decrypt(rows.get(0)[1]))));
        }
        KeyPair pair = WebPush.newKeyPair();
        String pub = WebPush.B64.encodeToString(WebPush.raw((ECPublicKey) pair.getPublic()));
        String priv = WebPush.B64.encodeToString(WebPush.raw((ECPrivateKey) pair.getPrivate()));
        jdbc.update("INSERT INTO push_vapid_key (id, public_key, private_key_enc, created_at) VALUES (1, ?, ?, ?)",
                pub, secrets.encrypt(priv), Timestamp.from(Instant.now()));
        log.info("PUSH created a VAPID key pair (set PUSH_VAPID_PUBLIC_KEY / PUSH_VAPID_PRIVATE_KEY to manage it yourself)");
        return new Keys(pub, (ECPrivateKey) pair.getPrivate());
    }
}
