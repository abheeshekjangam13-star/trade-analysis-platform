package com.tradeanalysis.strategy.share;

import com.tradeanalysis.strategy.model.Strategy;
import com.tradeanalysis.strategy.service.StrategyService;
import com.tradeanalysis.strategy.user.AppUser;
import com.tradeanalysis.strategy.user.AppUserRepository;
import com.tradeanalysis.strategy.user.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * Only an admin shares a strategy, with the users they choose. Each user gets an invitation (PENDING) showing the
 * strategy's rules; accepting it creates the user's own copy (it counts towards their plan's strategy limit and
 * remembers its source), declining it leaves nothing behind. The admin can withdraw an invitation not answered yet.
 */
@Service
public class StrategyShareService {

    private static final Logger log = LoggerFactory.getLogger(StrategyShareService.class);

    /** An invitation as the UI shows it: the strategy's rules come along so the user can decide. */
    public record ShareView(Long id, Long strategyId, String strategyName, String type, String description,
                            Map<String, String> parameters, Long sharedByUserId, String sharedBy, Long sharedWithUserId,
                            String sharedWith, StrategyShare.Status status, String note, Instant createdAt,
                            Instant respondedAt, Long createdStrategyId) {}

    private final StrategyShareRepository shares;
    private final StrategyService strategies;
    private final AppUserRepository users;

    public StrategyShareService(StrategyShareRepository shares, StrategyService strategies, AppUserRepository users) {
        this.shares = shares; this.strategies = strategies; this.users = users;
    }

    /** ADMIN: invites each user to take a copy; a user who already has an open invitation for it keeps that one. */
    @Transactional
    public List<ShareView> share(Long strategyId, Collection<Long> userIds, String note) {
        Long admin = requireAdmin();
        Strategy s = strategies.get(strategyId);
        if (userIds == null || userIds.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one user");
        String n = note == null || note.isBlank() ? null : note.trim();
        if (n != null && n.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Note is too long (max 500 characters)");
        List<ShareView> out = new ArrayList<>();
        for (Long uid : new LinkedHashSet<>(userIds)) {
            AppUser u = users.findById(uid).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "User not found: " + uid));
            if (u.getId().equals(admin)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can't share a strategy with yourself");
            if (u.getStatus() != AppUser.Status.ACTIVE)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, u.getUsername() + " is not an active user");
            StrategyShare sh = shares.findFirstByStrategyIdAndSharedWithUserIdAndStatus(s.getId(), uid, StrategyShare.Status.PENDING)
                    .orElseGet(() -> shares.save(new StrategyShare(s.getId(), admin, uid, n)));
            log.info("STRATEGY_SHARED strategy={} by={} with={}", s.getId(), admin, uid);
            out.add(view(sh));
        }
        return out;
    }

    /** ADMIN: every invitation for a strategy, newest first. */
    @Transactional(readOnly = true)
    public List<ShareView> forStrategy(Long strategyId) {
        requireAdmin();
        strategies.get(strategyId);
        return shares.findByStrategyIdOrderByCreatedAtDesc(strategyId).stream().map(this::view).toList();
    }

    /** The caller's invitations, newest first (pending ones first). */
    @Transactional(readOnly = true)
    public List<ShareView> mine() {
        Long me = me();
        return shares.findBySharedWithUserIdOrderByCreatedAtDesc(me).stream()
                .sorted(Comparator.comparing((StrategyShare s) -> s.getStatus() != StrategyShare.Status.PENDING))
                .map(this::view).toList();
    }

    /** Accepts an invitation: creates the caller's own copy of the strategy. */
    @Transactional
    public ShareView accept(Long shareId) {
        StrategyShare sh = pendingFor(shareId);
        Strategy src;
        try { src = strategies.load(sh.getStrategyId()); }
        catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.GONE, "This strategy is no longer available"); }
        String by = users.findById(sh.getSharedByUserId()).map(AppUser::getUsername).orElse("the admin");
        Strategy copy = strategies.copyShared(src.getId(), sh.getSharedWithUserId(), by);
        sh.accepted(copy.getId(), Instant.now());
        log.info("STRATEGY_SHARE_ACCEPTED share={} strategy={} copy={} user={}", sh.getId(), src.getId(), copy.getId(), sh.getSharedWithUserId());
        return view(shares.save(sh));
    }

    @Transactional
    public ShareView decline(Long shareId) {
        StrategyShare sh = pendingFor(shareId);
        sh.declined(Instant.now());
        return view(shares.save(sh));
    }

    /** ADMIN: withdraws an invitation the user hasn't answered yet. */
    @Transactional
    public ShareView revoke(Long shareId) {
        requireAdmin();
        StrategyShare sh = shares.findById(shareId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share not found: " + shareId));
        if (sh.getStatus() != StrategyShare.Status.PENDING)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This share was already " + sh.getStatus().name().toLowerCase());
        sh.revoked(Instant.now());
        return view(shares.save(sh));
    }

    private StrategyShare pendingFor(Long shareId) {
        Long me = me();
        StrategyShare sh = shares.findById(shareId).filter(s -> s.getSharedWithUserId().equals(me))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share not found: " + shareId));
        if (sh.getStatus() != StrategyShare.Status.PENDING)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This share was already " + sh.getStatus().name().toLowerCase());
        return sh;
    }

    private ShareView view(StrategyShare sh) {
        Strategy s = strategies.findAny(sh.getStrategyId());
        return new ShareView(sh.getId(), sh.getStrategyId(), s == null ? "Deleted strategy" : s.getName(),
                s == null ? null : s.getType(), s == null ? null : s.getDescription(),
                s == null || s.getParameters() == null ? Map.of() : new TreeMap<>(s.getParameters()),
                sh.getSharedByUserId(), name(sh.getSharedByUserId()), sh.getSharedWithUserId(), name(sh.getSharedWithUserId()),
                sh.getStatus(), sh.getNote(), sh.getCreatedAt(), sh.getRespondedAt(), sh.getCreatedStrategyId());
    }

    private String name(Long userId) { return users.findById(userId).map(AppUser::getUsername).orElse("Unknown user"); }

    private static Long me() {
        return CurrentUser.id().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Log in first"));
    }

    private static Long requireAdmin() {
        Long me = me();
        if (!CurrentUser.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an admin can share strategies");
        return me;
    }
}
