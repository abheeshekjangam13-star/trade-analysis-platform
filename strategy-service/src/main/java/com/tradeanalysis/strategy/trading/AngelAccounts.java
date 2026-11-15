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
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Users' own Angel One SmartAPI accounts (table angel_account), the alternative to {@link KiteAccounts}. A GOLD or
 * PREMIUM user saves the API key of their SmartAPI app (smartapi.angelone.in, redirect URL = this UI's
 * /angel/callback), then logs in through Angel One's publisher login every trading day; Angel redirects back with
 * that day's session tokens, which are checked against Angel (profile) and stored encrypted. No password, PIN or TOTP
 * is ever stored. A user has one broker: see {@link BrokerAccounts}.
 */
@Service
public class AngelAccounts {

    private static final Logger log = LoggerFactory.getLogger(AngelAccounts.class);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{4,64}");

    public record Status(boolean configured, String apiKey, boolean connected, String clientCode, String clientName,
                         Instant loggedInAt, String message) {
        static Status none() { return new Status(false, null, false, null, null, null, "Angel One account not connected"); }
    }

    private record Row(Long userId, String apiKey, String jwtEnc, String refreshEnc, String clientCode, String clientName,
                       Instant tokenUpdatedAt) {}

    private final JdbcTemplate jdbc;
    private final KiteSecrets secrets;
    private final AngelScripMaster symbols;
    private final NiftyOptions options;
    private final AppUserRepository users;
    private final ObjectMapper json;
    private final RestClient http;
    private final Clock clock;
    private final String loginUrl;

    public AngelAccounts(JdbcTemplate jdbc, KiteSecrets secrets, AngelScripMaster symbols, NiftyOptions options,
                         AppUserRepository users, ObjectMapper json, RestClient.Builder builder, Clock clock,
                         @Value("${angel.base-url:https://apiconnect.angelone.in}") String baseUrl,
                         @Value("${angel.login-url:https://smartapi.angelone.in/publisher-login}") String loginUrl,
                         @Value("${angel.public-ip:127.0.0.1}") String publicIp,
                         @Value("${angel.local-ip:127.0.0.1}") String localIp,
                         @Value("${angel.mac-address:00:00:00:00:00:00}") String mac) {
        this.jdbc = jdbc; this.secrets = secrets; this.symbols = symbols; this.options = options; this.users = users;
        this.json = json; this.clock = clock; this.loginUrl = loginUrl;
        this.http = builder.clone().baseUrl(baseUrl)
                .defaultHeader("X-UserType", "USER").defaultHeader("X-SourceID", "WEB")
                .defaultHeader("X-ClientLocalIP", localIp).defaultHeader("X-ClientPublicIP", publicIp)
                .defaultHeader("X-MACAddress", mac).build();
    }

    // ---- whose account trades -------------------------------------------------------------------------------------

    /** The user's own Angel One account (must be logged in today). */
    public KiteApi forUser(Long userId) {
        Row r = row(userId).orElseThrow(() -> new TradingException("Connect your own Angel One account first (Account page)"));
        if (r.jwtEnc() == null)
            throw new TradingException("Your Angel One account is not logged in: use Log in to Angel One on the Account page");
        return client(new AngelOneRestApi.Credentials(r.apiKey(), secrets.decrypt(r.jwtEnc()), r.clientCode()));
    }

    public boolean hasAccount(Long userId) { return userId != null && row(userId).isPresent(); }

    /** Made for each call so a test can replace it. */
    KiteApi client(AngelOneRestApi.Credentials creds) { return new AngelOneRestApi(http, creds, symbols, options, json); }

    // ---- the user's own account -----------------------------------------------------------------------------------

    public Status status(Long userId) {
        Row r = row(userId).orElse(null);
        if (r == null) return Status.none();
        if (r.jwtEnc() == null)
            return new Status(true, r.apiKey(), false, r.clientCode(), r.clientName(), null, "Not logged in to Angel One: log in to Angel One");
        try {
            KiteApi.Profile p = forUser(userId).profile();
            return new Status(true, r.apiKey(), true, p.userId(), p.userName(), r.tokenUpdatedAt(), "Connected");
        } catch (RuntimeException e) {
            return new Status(true, r.apiKey(), false, r.clientCode(), r.clientName(), r.tokenUpdatedAt(),
                    "Angel One session not valid (logins end every night): log in to Angel One again");
        }
    }

    /** Saves the API key of the user's SmartAPI app. A new key forgets the old login. */
    public Status saveApp(Long userId, String apiKey) {
        requirePaidPlan(userId);
        apiKey = apiKey == null ? "" : apiKey.trim();
        if (!KEY.matcher(apiKey).matches()) throw new TradingException("Enter the API key of your Angel One SmartAPI app");
        Row old = row(userId).orElse(null);
        Timestamp now = Timestamp.from(clock.instant());
        if (old == null) {
            jdbc.update("INSERT INTO angel_account (user_id, api_key, created_at, updated_at) VALUES (?, ?, ?, ?)", userId, apiKey, now, now);
        } else if (!old.apiKey().equals(apiKey)) {
            requireNoLiveStrategies(userId, "change the Angel One API key");
            jdbc.update("UPDATE angel_account SET api_key = ?, jwt_token_enc = NULL, refresh_token_enc = NULL, feed_token_enc = NULL, "
                    + "client_code = NULL, client_name = NULL, token_updated_at = NULL, updated_at = ? WHERE user_id = ?", apiKey, now, userId);
        }
        log.info("ANGEL_ACCOUNT_SAVED user={} apiKey={}", userId, apiKey);
        return status(userId);
    }

    /** Angel One's publisher login for the user's app; Angel redirects to /angel/callback?auth_token=…&feed_token=…&state=me */
    public String loginUrl(Long userId) {
        Row r = row(userId).orElseThrow(() -> new TradingException("Save your Angel One API key first"));
        return loginUrl + "?api_key=" + URLEncoder.encode(r.apiKey(), StandardCharsets.UTF_8) + "&state=me";
    }

    /** Stores today's session from the publisher login after checking it with Angel One (profile). */
    public Status connect(Long userId, String authToken, String refreshToken, String feedToken) {
        requirePaidPlan(userId);
        Row r = row(userId).orElseThrow(() -> new TradingException("Save your Angel One API key first"));
        String jwt = bare(authToken);
        if (jwt == null) throw new TradingException("Missing auth token: log in to Angel One again");
        KiteApi.Profile p;
        try {
            p = client(new AngelOneRestApi.Credentials(r.apiKey(), jwt, null)).profile();
        } catch (TradingException e) {
            throw new TradingException(e.getReason() + " (log in to Angel One again, and check the app's API key)");
        }
        if (p.userId() == null || p.userId().isBlank()) throw new TradingException("Angel One did not return your client code: log in again");
        Timestamp now = Timestamp.from(clock.instant());
        try {
            jdbc.update("UPDATE angel_account SET jwt_token_enc = ?, refresh_token_enc = ?, feed_token_enc = ?, client_code = ?, client_name = ?, "
                            + "token_updated_at = ?, updated_at = ? WHERE user_id = ?",
                    secrets.encrypt(jwt), secrets.encrypt(bare(refreshToken)), secrets.encrypt(bare(feedToken)), p.userId(), p.userName(),
                    now, now, userId);
        } catch (DuplicateKeyException e) {
            throw new TradingException(HttpStatus.CONFLICT, "Angel One client " + p.userId() + " is already connected to another user of this app");
        }
        log.info("ANGEL_ACCOUNT_LOGIN user={} client={}", userId, p.userId());
        return status(userId);
    }

    /** Forgets the user's Angel One app and login (refused while they have LIVE strategies). */
    public void disconnect(Long userId) {
        Row r = row(userId).orElse(null);
        if (r == null) return;
        requireNoLiveStrategies(userId, "disconnect your Angel One account");
        if (r.jwtEnc() != null && r.clientCode() != null) {
            try {   // best effort: end the session at Angel One too
                http.post().uri("/rest/secure/angelbroking/user/v1/logout").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + secrets.decrypt(r.jwtEnc())).header("X-PrivateKey", r.apiKey())
                        .body(Map.of("clientcode", r.clientCode())).retrieve().toBodilessEntity();
            } catch (RuntimeException e) {
                log.debug("Angel One logout for user {} failed: {}", userId, e.getMessage());
            }
        }
        jdbc.update("DELETE FROM angel_account WHERE user_id = ?", userId);
        log.info("ANGEL_ACCOUNT_REMOVED user={}", userId);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private Optional<Row> row(Long userId) {
        if (userId == null) return Optional.empty();
        return jdbc.query("SELECT user_id, api_key, jwt_token_enc, refresh_token_enc, client_code, client_name, token_updated_at "
                        + "FROM angel_account WHERE user_id = ?",
                rs -> rs.next() ? Optional.of(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant())) : Optional.empty(), userId);
    }

    private void requirePaidPlan(Long userId) {
        AppUser u = users.findById(userId).orElseThrow(() -> new TradingException(HttpStatus.UNAUTHORIZED, "Log in first"));
        if (!u.isAdmin() && !u.effectivePlan(clock.instant()).atLeast(Plan.GOLD))
            throw new TradingException(HttpStatus.FORBIDDEN, "Connecting your own Angel One account needs the GOLD or PREMIUM plan");
    }

    private void requireNoLiveStrategies(Long userId, String action) {
        Integer live = jdbc.queryForObject("SELECT COUNT(*) FROM strategy_user_mode WHERE user_id = ? AND mode = 'LIVE'", Integer.class, userId);
        if (live != null && live > 0) throw new TradingException("Switch your LIVE strategies to OFF or PAPER before you " + action);
    }

    /** The token without a "Bearer " prefix, or null when blank. */
    static String bare(String token) {
        if (token == null) return null;
        String t = token.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) t = t.substring(7).trim();
        return t.isEmpty() ? null : t;
    }
}
