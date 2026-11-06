package com.tradeanalysis.strategy.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeanalysis.strategy.user.AppUser;
import com.tradeanalysis.strategy.user.AppUserRepository;
import com.tradeanalysis.strategy.user.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Users' own Kite Connect accounts (table kite_account). A GOLD or PREMIUM user saves the API key and secret of their
 * own Kite Connect app (developers.kite.trade, redirect URL = this UI's /kite/callback), then logs in to Kite every
 * trading day; the request token Kite returns is exchanged here for that day's access token.
 *
 * <p>{@link #forUser} decides whose Kite account an order goes to: the user's own account when they connected one;
 * admins without one (and the system, user null) use the platform session (kite_session). Quotes, instruments and
 * paper fills always use the platform session (Kite's personal API plan has no market data).
 *
 * <p>Live trading on a user's own account needs the PREMIUM plan (admins: any plan).
 */
@Service
public class KiteAccounts {

    private static final Logger log = LoggerFactory.getLogger(KiteAccounts.class);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9]{6,64}");

    public record Status(boolean configured, String apiKey, boolean connected, String kiteUserId, String kiteUserName,
                         Instant loggedInAt, String message) {
        static Status none() { return new Status(false, null, false, null, null, null, "Kite account not connected"); }
    }

    private record Row(Long userId, String apiKey, String apiSecretEnc, String accessTokenEnc, String kiteUserId,
                       String kiteUserName, Instant tokenUpdatedAt) {}

    private final JdbcTemplate jdbc;
    private final KiteSecrets secrets;
    private final KiteApi platform;
    private final AppUserRepository users;
    private final ObjectMapper json;
    private final RestClient http;
    private final Clock clock;
    private final String loginUrl;

    public KiteAccounts(JdbcTemplate jdbc, KiteSecrets secrets, KiteApi platform, AppUserRepository users, ObjectMapper json,
                        RestClient.Builder builder, Clock clock,
                        @Value("${kite.base-url:https://api.kite.trade}") String baseUrl,
                        @Value("${kite.login-url:https://kite.zerodha.com/connect/login}") String loginUrl) {
        this.jdbc = jdbc; this.secrets = secrets; this.platform = platform; this.users = users; this.json = json;
        this.http = builder.clone().baseUrl(baseUrl).defaultHeader("X-Kite-Version", "3").build();
        this.clock = clock; this.loginUrl = loginUrl;
    }

    // ---- whose account trades -------------------------------------------------------------------------------------

    /** The Kite account for this user's orders and broker views. */
    public KiteApi forUser(Long userId) {
        Optional<Row> own = userId == null ? Optional.empty() : row(userId);
        if (own.isPresent()) {
            Row r = own.get();
            if (r.accessTokenEnc() == null)
                throw new TradingException("Your Kite account is not logged in: use Log in to Kite on the Account page");
            return client(new KiteSession.Credentials(r.apiKey(), secrets.decrypt(r.accessTokenEnc()), r.kiteUserId(), r.kiteUserName()));
        }
        if (userId == null || isAdmin(userId)) return platform;
        throw new TradingException("Connect your own Kite account first (Account page)");
    }

    /** Whether the user trades on their own Kite account (otherwise, for admins, the platform account). */
    public boolean hasOwnAccount(Long userId) { return userId != null && row(userId).isPresent(); }

    /** Why this user may not open LIVE positions, or null if they may (system/admins always; others: PREMIUM + own account). */
    public String liveRefusal(Long userId) {
        if (userId == null) return null;
        AppUser u = users.findById(userId).orElse(null);
        if (u == null) return "Unknown user";
        if (u.isAdmin()) return null;
        if (!u.effectivePlan(clock.instant()).atLeast(Plan.PREMIUM))
            return "Live trading needs the PREMIUM plan (your plan is " + u.effectivePlan(clock.instant()) + ")";
        if (row(userId).isEmpty()) return "Connect your own Kite account first (Account page)";
        return null;
    }

    /** Made for each call so a test can replace it. */
    KiteApi client(KiteSession.Credentials creds) { return KiteRestApi.of(http, creds, json); }

    // ---- the user's own account -----------------------------------------------------------------------------------

    public Status status(Long userId) {
        Row r = row(userId).orElse(null);
        if (r == null) return Status.none();
        if (r.accessTokenEnc() == null)
            return new Status(true, r.apiKey(), false, r.kiteUserId(), r.kiteUserName(), null, "Not logged in to Kite: log in to Kite");
        try {
            KiteApi.Profile p = forUser(userId).profile();
            return new Status(true, r.apiKey(), true, p.userId(), p.userName(), r.tokenUpdatedAt(), "Connected");
        } catch (RuntimeException e) {
            return new Status(true, r.apiKey(), false, r.kiteUserId(), r.kiteUserName(), r.tokenUpdatedAt(),
                    "Kite session not valid (Kite logins expire every morning): log in to Kite again");
        }
    }

    /**
     * Saves the API key and secret of the user's Kite Connect app. A blank secret keeps the saved one (same key only).
     * A new key forgets the old login.
     */
    public Status saveApp(Long userId, String apiKey, String apiSecret) {
        requirePaidPlan(userId);
        apiKey = apiKey == null ? "" : apiKey.trim();
        apiSecret = apiSecret == null ? "" : apiSecret.trim();
        if (!KEY.matcher(apiKey).matches()) throw new TradingException("Enter the API key of your Kite Connect app (letters and digits)");
        Row old = row(userId).orElse(null);
        boolean sameKey = old != null && old.apiKey().equals(apiKey);
        if (apiSecret.isEmpty() && !sameKey) throw new TradingException("Enter the API secret of your Kite Connect app");
        if (!apiSecret.isEmpty() && !KEY.matcher(apiSecret).matches()) throw new TradingException("The API secret has letters and digits only");
        Timestamp now = Timestamp.from(clock.instant());
        String secretEnc = apiSecret.isEmpty() ? old.apiSecretEnc() : secrets.encrypt(apiSecret);
        if (old == null) {
            jdbc.update("INSERT INTO kite_account (user_id, api_key, api_secret_enc, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    userId, apiKey, secretEnc, now, now);
        } else if (sameKey) {
            jdbc.update("UPDATE kite_account SET api_secret_enc = ?, updated_at = ? WHERE user_id = ?", secretEnc, now, userId);
        } else {
            requireNoLiveStrategies(userId, "change the Kite API key");
            jdbc.update("UPDATE kite_account SET api_key = ?, api_secret_enc = ?, access_token_enc = NULL, kite_user_id = NULL, "
                    + "kite_user_name = NULL, token_updated_at = NULL, updated_at = ? WHERE user_id = ?", apiKey, secretEnc, now, userId);
        }
        log.info("KITE_ACCOUNT_SAVED user={} apiKey={}", userId, apiKey);
        return status(userId);
    }

    /** Kite's login page for the user's own app; Kite redirects back to /kite/callback?…&account=me&request_token=… */
    public String loginUrl(Long userId) {
        Row r = row(userId).orElseThrow(() -> new TradingException("Save your Kite API key and secret first"));
        return loginUrl + "?v=3&api_key=" + URLEncoder.encode(r.apiKey(), StandardCharsets.UTF_8)
                + "&redirect_params=" + URLEncoder.encode("account=me", StandardCharsets.UTF_8);
    }

    /** Exchanges the request token from the Kite login for today's access token (stored encrypted). */
    public Status connect(Long userId, String requestToken) {
        requirePaidPlan(userId);
        Row r = row(userId).orElseThrow(() -> new TradingException("Save your Kite API key and secret first"));
        if (requestToken == null || requestToken.isBlank()) throw new TradingException("Missing request token: log in to Kite again");
        String secret = secrets.decrypt(r.apiSecretEnc());
        Map<String, Object> data = createSession(r.apiKey(), requestToken.trim(), secret);
        String token = str(data.get("access_token"));
        String kiteUser = str(data.get("user_id"));
        if (token == null || token.isBlank()) throw new TradingException("Kite did not return an access token: log in to Kite again");
        try {
            jdbc.update("UPDATE kite_account SET access_token_enc = ?, kite_user_id = ?, kite_user_name = ?, token_updated_at = ?, updated_at = ? "
                            + "WHERE user_id = ?", secrets.encrypt(token), kiteUser, str(data.get("user_name")),
                    Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), userId);
        } catch (DuplicateKeyException e) {
            throw new TradingException(HttpStatus.CONFLICT, "Kite account " + kiteUser + " is already connected to another user of this app");
        }
        log.info("KITE_ACCOUNT_LOGIN user={} kiteUser={}", userId, kiteUser);
        return status(userId);
    }

    /** Forgets the user's Kite app and login (refused while they have LIVE strategies). */
    public void disconnect(Long userId) {
        Row r = row(userId).orElse(null);
        if (r == null) return;
        requireNoLiveStrategies(userId, "disconnect your Kite account");
        if (r.accessTokenEnc() != null) {
            try {   // best effort: end the session at Kite too
                String token = secrets.decrypt(r.accessTokenEnc());
                http.delete().uri(u -> u.path("/session/token").queryParam("api_key", r.apiKey()).queryParam("access_token", token).build())
                        .header("Authorization", "token " + r.apiKey() + ":" + token).retrieve().toBodilessEntity();
            } catch (RuntimeException e) {
                log.debug("Kite logout for user {} failed: {}", userId, e.getMessage());
            }
        }
        jdbc.update("DELETE FROM kite_account WHERE user_id = ?", userId);
        log.info("KITE_ACCOUNT_REMOVED user={}", userId);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    Map<String, Object> createSession(String apiKey, String requestToken, String apiSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("api_key", apiKey);
        form.add("request_token", requestToken);
        form.add("checksum", sha256(apiKey + requestToken + apiSecret));
        try {
            Map<String, Object> body = http.post().uri("/session/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (!(data instanceof Map<?, ?> m)) throw new TradingException("Kite returned no session: log in to Kite again");
            return (Map<String, Object>) m;
        } catch (RestClientResponseException e) {
            throw new TradingException(kiteMessage(e) + " (check the API secret, and log in to Kite again: a login works once, for a few minutes)");
        }
    }

    private Optional<Row> row(Long userId) {
        return jdbc.query("SELECT user_id, api_key, api_secret_enc, access_token_enc, kite_user_id, kite_user_name, token_updated_at "
                        + "FROM kite_account WHERE user_id = ?",
                rs -> rs.next() ? Optional.of(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant())) : Optional.empty(), userId);
    }

    private boolean isAdmin(Long userId) { return users.findById(userId).map(AppUser::isAdmin).orElse(false); }

    private void requirePaidPlan(Long userId) {
        AppUser u = users.findById(userId).orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first"));
        if (!u.isAdmin() && !u.effectivePlan(clock.instant()).atLeast(Plan.GOLD))
            throw new TradingException(HttpStatus.FORBIDDEN, "Connecting your own Kite account needs the GOLD or PREMIUM plan");
    }

    private void requireNoLiveStrategies(Long userId, String action) {
        Integer live = jdbc.queryForObject("SELECT COUNT(*) FROM strategy_user_mode WHERE user_id = ? AND mode = 'LIVE'", Integer.class, userId);
        if (live != null && live > 0) throw new TradingException("Switch your LIVE strategies to OFF or PAPER before you " + action);
    }

    private String kiteMessage(RestClientResponseException e) {
        try {
            Object msg = json.readValue(e.getResponseBodyAsString(), Map.class).get("message");
            if (msg != null) return "Kite: " + msg;
        } catch (Exception ignored) { }
        return "Kite error " + e.getStatusCode().value();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
