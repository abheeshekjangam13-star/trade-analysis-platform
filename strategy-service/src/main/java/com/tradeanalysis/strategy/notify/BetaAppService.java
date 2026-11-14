package com.tradeanalysis.strategy.notify;

import com.tradeanalysis.strategy.user.AppUser;
import com.tradeanalysis.strategy.user.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Beta mobile app, phase 1. An admin nominates users (about 10% of them, picked at random in the UI): each gets a
 * congratulations email asking them to request the app link on My account. When they do, the link email goes out at
 * once. Only nominated users can request it.
 */
@Service
public class BetaAppService {

    /** A user can ask for the link again (e.g. lost the email), but not more often than this. */
    static final Duration RESEND_GAP = Duration.ofMinutes(5);

    public record Status(Long userId, Instant nominatedAt, Instant linkRequestedAt, int linksSent) {}
    public record NominateResult(List<String> nominated, List<String> skipped, long totalUsers) {}
    public record Mine(boolean nominated, Instant nominatedAt, Instant linkRequestedAt, int linksSent) {}

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EmailService emails;
    private final Clock clock;

    public BetaAppService(JdbcTemplate jdbc, AppUserRepository users, EmailService emails, Clock clock) {
        this.jdbc = jdbc; this.users = users; this.emails = emails; this.clock = clock;
    }

    /** Active users: the "out of N users" in the nomination email. */
    public long totalUsers() {
        return users.findAll().stream().filter(u -> u.getStatus() == AppUser.Status.ACTIVE).count();
    }

    /**
     * ADMIN: nominates the users and queues their congratulations email. Only ACTIVE users with an email address;
     * the others come back in {@code skipped} with the reason. Nominating someone again re-sends the email.
     */
    @Transactional
    public NominateResult nominate(Collection<Long> userIds, String note, Long adminId) {
        List<String> nominated = new ArrayList<>(), skipped = new ArrayList<>();
        String n = note == null || note.isBlank() ? null : note.trim();
        long total = totalUsers();
        Timestamp now = Timestamp.from(clock.instant());
        for (Long id : new LinkedHashSet<>(userIds)) {
            AppUser u = users.findById(id).orElse(null);
            if (u == null) { skipped.add("#" + id + ": no such user"); continue; }
            if (u.getStatus() != AppUser.Status.ACTIVE) { skipped.add(u.getUsername() + ": not active (" + u.getStatus() + ")"); continue; }
            if (!hasEmail(u)) { skipped.add(u.getUsername() + ": no email address"); continue; }
            int updated = jdbc.update("UPDATE beta_app_user SET nominated_at = ?, nominated_by = ? WHERE user_id = ?", now, adminId, id);
            if (updated == 0) jdbc.update("INSERT INTO beta_app_user (user_id, nominated_at, nominated_by, links_sent) VALUES (?, ?, ?, 0)", id, now, adminId);
            emails.betaInvite(u, total, n);
            nominated.add(u.getUsername());
        }
        return new NominateResult(nominated, skipped, total);
    }

    /** ADMIN: every nominated user's status (user id → status). */
    @Transactional(readOnly = true)
    public Map<Long, Status> statuses() {
        Map<Long, Status> out = new HashMap<>();
        jdbc.query("SELECT user_id, nominated_at, link_requested_at, links_sent FROM beta_app_user", rs -> {
            long id = rs.getLong(1);
            out.put(id, new Status(id, instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3)), rs.getInt(4)));
        });
        return out;
    }

    /** The logged-in user's beta status (not nominated: all empty). */
    @Transactional(readOnly = true)
    public Mine mine(Long userId) {
        List<Mine> rows = jdbc.query("SELECT nominated_at, link_requested_at, links_sent FROM beta_app_user WHERE user_id = ?",
                (rs, i) -> new Mine(true, instant(rs.getTimestamp(1)), instant(rs.getTimestamp(2)), rs.getInt(3)), userId);
        return rows.isEmpty() ? new Mine(false, null, null, 0) : rows.get(0);
    }

    /** A nominated user asks for the app link: it's emailed at once (at most once every {@link #RESEND_GAP}). */
    @Transactional
    public Mine requestLink(Long userId) {
        Mine m = mine(userId);
        if (!m.nominated()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The beta app is open to invited users only for now");
        Instant now = clock.instant();
        if (m.linkRequestedAt() != null && m.linkRequestedAt().plus(RESEND_GAP).isAfter(now))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "We just sent your link: please check your inbox (and spam folder) before asking again");
        AppUser u = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Log in first"));
        if (!hasEmail(u)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add an email address to your account first");
        jdbc.update("UPDATE beta_app_user SET link_requested_at = ?, links_sent = links_sent + 1 WHERE user_id = ?", Timestamp.from(now), userId);
        emails.appLink(u);
        return mine(userId);
    }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }

    private static boolean hasEmail(AppUser u) { return u.getEmail() != null && !u.getEmail().isBlank(); }
}
